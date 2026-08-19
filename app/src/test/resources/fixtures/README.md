# Fictional Frigate test data

> This page describes test data for developers. It is not needed to install or
> use Opah.

These files contain fictional Frigate data used by automated tests. They keep
only the shapes needed to test cameras, streams, video formats, Review, and
Birdseye. They do not describe or connect to a real Frigate server.

`frigate_config_0_17_sanitized.json` is a fictional Frigate 0.17-style
configuration. It keeps enough structure to test camera order and live-stream
choices. It contains no real camera names, addresses, usernames, passwords,
tokens, or recordings. It is test data, not an official Frigate specification.

`go2rtc_streams_sanitized.json` and `review_items_sanitized.json` are also
fictional. Reserved example addresses and fixed dates ensure they cannot
identify or contact a real installation.

Do not replace these files with information copied from a live Frigate server.
Add only the smallest amount of fictional data a repeatable test needs.
