Radio Guaka
===========

**Radio Guaka** is an Internet Radio player based on HTML5 and vanilla JavaScript. Currently there are only [ad-free](http://moneyless.org/tags/advertising) radio stations, such as SomaFM and Radio Paradise.

## About

This is a static site that can be deployed on GitHub Pages. It was converted from a Meteor.js application to a simpler static site using Alpine.js for reactivity.

**Note:** The original Meteor.js codebase is preserved in the git history. The old Meteor-specific files (in `client/` and `server/` directories) are no longer used but kept for reference. The current implementation uses:
- Alpine.js for reactivity
- Vanilla JavaScript (converted from CoffeeScript)
- Plain CSS (converted from Bootstrap)
- History API for routing

## Channels

Channels are defined in `channels.js`. You're welcome to add channels and metadata by editing (i.e. fork and send pull requests).

Active channels are in the main `channels` object. Broken/non-working channels are kept in comments for reference.

## Local Development

Simply serve the files with any static HTTP server:

```bash
# Using Python 3
python3 -m http.server 8000

# Using Node.js http-server
npx http-server

# Using PHP
php -S localhost:8000
```

Then open http://localhost:8000 in your browser.

## GitHub Pages Deployment

1. Push the code to a GitHub repository
2. Go to repository Settings → Pages
3. Select the branch (usually `main` or `master`)
4. Select the root directory (`/`)
5. Save

The site will be available at `https://username.github.io/radio-meteor/`

The `404.html` file enables History API routing on GitHub Pages by redirecting all routes to `index.html`.

## Features

- Keyboard shortcuts:
  - Arrow keys ← → : Change channels
  - Arrow keys ↑ ↓ : Change volume
  - Space : Stop/pause
  - Alt + letter : Jump to channel starting with that letter

## Background

Radio Guaka was inspired by [somafm-popup](https://github.com/joe-roth/somafm-popup) and the need for a nice way to play Soma.fm streams, [Radio Paradise](http://www.radioparadise.com/), and more.

## Links

* [radio.guaka.org](http://radio.guaka.org/)
* [Mozilla Dev resources](https://developer.mozilla.org/en-US/docs/DOM/HTMLMediaElement)
