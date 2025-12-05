Radio Guaka
===========

**Radio Guaka** is an Internet Radio player based on HTML5 and vanilla JavaScript.
It's not Guaka's music, but radio stations that Guaka likes listening to, such as SomaFM and Radio Paradise.

Started in 2013 using meteorjs, completely revibed in 2025.


## About

This is a static site that can be deployed on GitHub Pages. The current implementation uses:
- Alpine.js for reactivity
- Vanilla JavaScript
- Plain CSS
- History API for routing

## Channels

Channels are defined in `channels.js`. You're welcome to add channels and metadata by editing (i.e. fork and send pull requests).

Active channels are in the main `channels` object. Broken/non-working channels are kept in comments for reference.


## Features

- Keyboard shortcuts:
  - Arrow keys ← → : Change channels
  - Arrow keys ↑ ↓ : Change volume
  - Space : Stop/pause
  - Letter key : Jump to channel starting with that letter (no modifier needed)

## Background

Radio Guaka was inspired by [somafm-popup](https://github.com/joe-roth/somafm-popup) and the need for a nice way to play Soma.fm streams, [Radio Paradise](http://www.radioparadise.com/), and more.

## Links

* [radio.guaka.org](http://radio.guaka.org/)
