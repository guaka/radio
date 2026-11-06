package httpapi

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// Match the complete origin before accepting any user-supplied path or query.
// Hosts for mirrors come from the server's configured list, never the request.
var youtubeStreamURLPattern = func() *regexp.Regexp {
	hosts := []string{`(?:[a-z0-9-]+\.)*googlevideo\.com`}
	for _, base := range pipedAPIInstances {
		u, err := url.Parse(base)
		if err == nil {
			hosts = append(hosts, regexp.QuoteMeta(strings.Replace(u.Hostname(), "pipedapi.", "pipedproxy.", 1)))
		}
	}
	return regexp.MustCompile(`(?i)^https://(?:` + strings.Join(hosts, "|") + `)(?::443)?(?:/|$)`)
}()

type youtubeStreamTransport struct{ transport http.RoundTripper }

var youtubeStreamHTTPClient = newYouTubeStreamClient()

func (t youtubeStreamTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	if !allowedYouTubeStreamURL(req.URL) {
		return nil, errors.New("stream host not allowed")
	}
	return t.transport.RoundTrip(req)
}

func newYouTubeStreamClient() *http.Client {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	transport := &http.Transport{
		// Do not use environment proxies: resolve and validate the actual peer here.
		DialContext:           youtubeStreamDialer(net.DefaultResolver.LookupNetIP, dialer.DialContext),
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		IdleConnTimeout:       90 * time.Second,
	}
	return &http.Client{
		Transport: youtubeStreamTransport{transport},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 || !allowedYouTubeStreamURL(req.URL) {
				return errors.New("stream redirect not allowed")
			}
			return nil
		},
	}
}

// Resolve once, reject non-public answers, then dial the validated IP directly.
// This prevents a second DNS lookup from rebinding an allowed host to a private peer.
func youtubeStreamDialer(
	lookup func(context.Context, string, string) ([]netip.Addr, error),
	dial func(context.Context, string, string) (net.Conn, error),
) func(context.Context, string, string) (net.Conn, error) {
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil || port != "443" {
			return nil, errors.New("stream port not allowed")
		}
		ips, err := lookup(ctx, "ip", host)
		if err != nil {
			return nil, err
		}
		if len(ips) == 0 {
			return nil, errors.New("stream host has no addresses")
		}
		for _, ip := range ips {
			if !publicStreamIP(ip) {
				return nil, errors.New("stream address not allowed")
			}
		}
		for _, ip := range ips {
			var conn net.Conn
			conn, err = dial(ctx, network, net.JoinHostPort(ip.Unmap().String(), port))
			if err == nil {
				return conn, nil
			}
		}
		return nil, fmt.Errorf("stream connection failed: %w", err)
	}
}

var nonPublicStreamPrefixes = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),
	netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("192.0.0.0/24"),
	netip.MustParsePrefix("192.0.2.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"),
	netip.MustParsePrefix("198.51.100.0/24"),
	netip.MustParsePrefix("203.0.113.0/24"),
	netip.MustParsePrefix("240.0.0.0/4"),
	netip.MustParsePrefix("2001:db8::/32"),
}

func publicStreamIP(ip netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.Zone() != "" {
		return false
	}
	for _, prefix := range nonPublicStreamPrefixes {
		if prefix.Contains(ip) {
			return false
		}
	}
	return true
}
