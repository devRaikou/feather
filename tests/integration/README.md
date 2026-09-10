# Large world regression probe

Build Feather with `mvn -pl slimeworldmanager-plugin -am clean verify`, then run
this opt-in integration test with Java 21, Python 3, a Swift 1.8.8 server jar, and
an existing Anvil map. It starts a temporary server bound only to localhost on an
automatically allocated port and leaves its logs in the printed temporary folder.
The source map is read only, and its files are hashed before and after the test.

```sh
python3 tests/integration/run-large-world-probe.py \
  --server-jar /path/to/swift-server.jar \
  --world /path/to/Shady_Hollow1 \
  --ender-chests 68 --heap 3g
```

The probe loads the real map, creates a runtime clone, checks that generation
converts fewer than 1,024 chunks, visits two ender chest chunks, checks repeated
reads reuse the converted chunk, changes one chest's orientation, saves, unloads,
and reopens the result. It checks the block, orientation, and tile entity of every
ender chest, including untouched chunks. A GC before reopening isolates retained
world data from temporary allocations. Source files and map contents are not
bundled in the repository.

Shady Hollow has 49,896 chunks and 68 ender chests. Keep the same heap limit when
comparing versions; use `--feather-jar` to select an older built jar.
