# Channel Surfer lineup

The [Android Channel Surfer app](../../README.md) can load this smaller playlist:

`https://raw.githubusercontent.com/jakerman74/iptv/master/channel-surfer/lineup.m3u`

This lineup is generated from IPTV-org's U.S. playlist. It includes selected program categories, one explicitly added Chicago NBC stream, and HTTPS stream URLs only. A listing does not guarantee that a stream works, is available in your location, or carries the program you expect.

## Prune or add channels

Edit [rules.json](rules.json):

- `categories`: include channels carrying any named category.
- `include_ids`: add individual `tvg-id` values even if their category is omitted.
- `exclude_ids`: remove all streams for a particular `tvg-id`.
- `exclude_urls`: remove one specific stream URL.

An excluded ID takes priority over an included ID. The app's **Hide channel** control prunes the lineup on that device; it does not edit this GitHub file. To regenerate and commit the shared list after changing the rules, run `python3 channel-surfer/build_playlist.py` from a checkout of this fork. The script fetches the current upstream U.S. playlist, limits it to 25 MB, and refuses to replace the list with an empty result.

The generated playlist remains part of this fork and is not submitted to the upstream IPTV-org project.
