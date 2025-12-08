#!/usr/bin/env python3
"""
Test script to verify Radio Paradise streams are working.
"""

import urllib.request
import urllib.error
import ssl

# Radio Paradise streams - OLD URLs (currently in channels.js)
OLD_PARADISE_STREAMS = {
    'paradise': 'https://stream-uk1.radioparadise.com/mp3-128',
    'paradise_mellow': 'https://stream.radioparadise.com/mellow-mp3-128',
    'paradise_rock': 'https://stream.radioparadise.com/rock-mp3-128',
    'paradise_global': 'https://stream.radioparadise.com/global-mp3-128',
    'paradise_world': 'https://stream.radioparadise.com/world-mp3-128',
    'paradise_eclectic': 'https://stream.radioparadise.com/eclectic-mp3-128',
}

# Radio Paradise streams - NEW URLs (from API)
# Using AAC-128 for better browser compatibility (MP3-192 also available)
NEW_PARADISE_STREAMS = {
    'paradise': 'https://stream.radioparadise.com/aac-128',          # Main Mix
    'paradise_mellow': 'https://stream.radioparadise.com/mellow-128', # Mellow Mix  
    'paradise_rock': 'https://stream.radioparadise.com/rock-128',     # Rock Mix
    'paradise_global': 'https://stream.radioparadise.com/global-128', # Global Mix
    'paradise_beyond': 'https://stream.radioparadise.com/beyond-128', # Beyond Mix (new!)
    'paradise_serenity': 'https://stream.radioparadise.com/serenity', # Serenity (new!)
    'paradise_2050': 'https://stream.radioparadise.com/radio2050-128', # Radio 2050 (new!)
}

def test_stream(name, url):
    """Test if a stream URL is working by trying to read some data from it."""
    print(f"\nTesting {name}...")
    print(f"  URL: {url}")
    
    try:
        req = urllib.request.Request(
            url,
            headers={
                'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Radio Guaka Stream Tester',
            }
        )
        
        ctx = ssl.create_default_context()
        
        with urllib.request.urlopen(req, timeout=10, context=ctx) as response:
            data = response.read(4096)
            status = response.status
            content_type = response.headers.get('Content-Type', 'unknown')
            
            if data and len(data) > 0:
                print(f"  ✅ WORKING - Status: {status}, Content-Type: {content_type}, Got {len(data)} bytes")
                return True, None
            else:
                print(f"  ❌ FAILED - No data received")
                return False, "No data received"
                
    except urllib.error.HTTPError as e:
        print(f"  ❌ FAILED - HTTP Error: {e.code} {e.reason}")
        return False, f"HTTP {e.code}: {e.reason}"
    except urllib.error.URLError as e:
        print(f"  ❌ FAILED - URL Error: {e.reason}")
        return False, str(e.reason)
    except TimeoutError:
        print(f"  ❌ FAILED - Connection timed out")
        return False, "Timeout"
    except Exception as e:
        print(f"  ❌ FAILED - {type(e).__name__}: {e}")
        return False, str(e)


def test_streams(streams, title):
    """Test a set of streams and return results."""
    print("\n" + "=" * 60)
    print(title)
    print("=" * 60)
    
    working = []
    broken = []
    
    for name, url in streams.items():
        success, error = test_stream(name, url)
        if success:
            working.append(name)
        else:
            broken.append((name, error))
    
    return working, broken


def main():
    # Test old URLs
    old_working, old_broken = test_streams(OLD_PARADISE_STREAMS, "Testing OLD URLs (currently in channels.js)")
    
    # Test new URLs  
    new_working, new_broken = test_streams(NEW_PARADISE_STREAMS, "Testing NEW URLs (from Radio Paradise API)")
    
    # Summary
    print("\n" + "=" * 60)
    print("SUMMARY")
    print("=" * 60)
    
    print(f"\nOLD URLs:")
    print(f"  ✅ Working: {len(old_working)} - {', '.join(old_working) if old_working else 'none'}")
    print(f"  ❌ Broken:  {len(old_broken)} - {', '.join([b[0] for b in old_broken]) if old_broken else 'none'}")
    
    print(f"\nNEW URLs:")
    print(f"  ✅ Working: {len(new_working)} - {', '.join(new_working) if new_working else 'none'}")
    print(f"  ❌ Broken:  {len(new_broken)} - {', '.join([b[0] for b in new_broken]) if new_broken else 'none'}")
    
    if new_working:
        print("\n" + "=" * 60)
        print("RECOMMENDED channels.js UPDATE")
        print("=" * 60)
        print("\nReplace Radio Paradise section with:")
        print()
        print("  // Radio Paradise")
        for name in sorted(NEW_PARADISE_STREAMS.keys()):
            url = NEW_PARADISE_STREAMS[name]
            if name == 'paradise':
                tags = "['paradise', 'US', 'rock', 'eclectic']"
            elif name == 'paradise_mellow':
                tags = "['paradise', 'US', 'mellow', 'acoustic']"
            elif name == 'paradise_rock':
                tags = "['paradise', 'US', 'rock']"
            elif name == 'paradise_global':
                tags = "['paradise', 'US', 'global', 'world']"
            elif name == 'paradise_beyond':
                tags = "['paradise', 'US', 'electronic', 'ambient']"
            elif name == 'paradise_serenity':
                tags = "['paradise', 'US', 'ambient', 'relaxation']"
            elif name == 'paradise_2050':
                tags = "['paradise', 'US', 'electronic', 'future']"
            else:
                tags = "['paradise', 'US']"
            print(f"  '{name}': {{")
            print(f"    url: '{url}',")
            print(f"    tags: {tags},")
            print(f"    site: 'https://www.radioparadise.com/'")
            print(f"  }},")
    
    return len(new_broken) > 0


if __name__ == '__main__':
    has_issues = main()
    exit(1 if has_issues else 0)
