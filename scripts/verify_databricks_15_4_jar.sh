#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <connector-assembly.jar>" >&2
  exit 2
fi

jar_path="$1"
if [ ! -f "$jar_path" ]; then
  echo "Connector JAR not found: $jar_path" >&2
  exit 1
fi

entries="$(mktemp)"
native_dir="$(mktemp -d)"
trap 'rm -f "$entries"; rm -rf "$native_dir"' EXIT

jar tf "$jar_path" > "$entries"

require_entry() {
  if ! grep -Fxq "$1" "$entries"; then
    echo "Required JAR entry is missing: $1" >&2
    exit 1
  fi
}

reject_prefix() {
  if grep -Eq "^$1" "$entries"; then
    echo "Runtime-provided classes must not be bundled: $1" >&2
    exit 1
  fi
}

require_entry "META-INF/services/org.apache.spark.sql.sources.DataSourceRegister"
require_entry "native/linux-x86_64/libmilvus-storage.so"
require_entry "native/linux-x86_64/libmilvus-storage-jni.so"
require_entry "native/linux-x86_64/libaio.so.1"
require_entry "native/linux-x86_64/libatomic.so.1"
require_entry "native/linux-x86_64/libgomp.so.1"
require_entry "META-INF/native-licenses/libaio1.copyright"
require_entry "META-INF/native-licenses/gcc-runtime.copyright"

reject_prefix "org/apache/spark/"
reject_prefix "org/apache/hadoop/"
reject_prefix "software/amazon/awssdk/"
reject_prefix "com/amazonaws/"
reject_prefix "com/fasterxml/jackson/"
reject_prefix "io/netty/"
reject_prefix "scala/"
reject_prefix "org/apache/avro/"
reject_prefix "org/apache/arrow/flatbuf/"
reject_prefix "org/apache/arrow/memory/"
reject_prefix "org/apache/parquet/column/"
reject_prefix "org/apache/parquet/hadoop/"
reject_prefix "org/apache/parquet/io/"
reject_prefix "org/apache/parquet/schema/"

# arrow-c-data owns these bridge classes even though they live in the vector
# package; arrow-vector itself does not contain them. Reject every other Arrow
# vector entry so Databricks remains the source of its runtime Arrow classes.
unexpected_arrow_vector_entries="$(
  grep -E '^org/apache/arrow/vector/' "$entries" \
    | grep -Ev '^org/apache/arrow/vector/$|^org/apache/arrow/vector/StructVector(Loader|Unloader)\.class$' \
    || true
)"
if [ -n "$unexpected_arrow_vector_entries" ]; then
  echo "Runtime-provided Arrow Vector classes must not be bundled:" >&2
  printf '%s\n' "$unexpected_arrow_vector_entries" >&2
  exit 1
fi

python3 - "$jar_path" <<'PY'
import sys
import zipfile

jar_path = sys.argv[1]
invalid = []
with zipfile.ZipFile(jar_path) as jar:
    for entry in jar.infolist():
        if not entry.filename.endswith(".class"):
            continue
        with jar.open(entry) as stream:
            header = stream.read(8)
        if len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe":
            invalid.append((entry.filename, "invalid class header"))
            continue
        major = int.from_bytes(header[6:8], byteorder="big")
        if major > 52:
            invalid.append((entry.filename, "class major version %d" % major))

if invalid:
    for name, reason in invalid[:20]:
        print("Java 8 compatibility failure: %s: %s" % (name, reason), file=sys.stderr)
    if len(invalid) > 20:
        print("... and %d more" % (len(invalid) - 20), file=sys.stderr)
    sys.exit(1)
PY

unzip -q "$jar_path" 'native/linux-x86_64/*' -d "$native_dir"
jni_library="$native_dir/native/linux-x86_64/libmilvus-storage-jni.so"

if ! file "$jni_library" | grep -q 'ELF 64-bit.*x86-64'; then
  echo "JNI library is not a Linux x86_64 ELF binary" >&2
  file "$jni_library" >&2
  exit 1
fi

ldd_output="$(env -u LD_LIBRARY_PATH -u LD_PRELOAD ldd "$jni_library")"
if grep -q 'not found' <<<"$ldd_output"; then
  echo "JNI library has unresolved dependencies:" >&2
  grep 'not found' <<<"$ldd_output" >&2
  exit 1
fi

echo "Verified DBR 15.4 JAR: $jar_path"
