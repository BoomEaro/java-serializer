Provenance
==========
Baseline: d0299c9df50a01ec8624e7fda6847b812ccf1944 (before this repair series).
Generated on 2026-10-10 with Amazon Corretto 21.0.12.
The archived baseline production sources were compiled with javac --release 17.
LegacyCompatibilityRegressionTest.java was compiled ONCE against that baseline
JAR, then the identical client class files ran with either baseline or current
library classes. This fixture directory contains baseline output only.

Each .yml/.json file is the exact UTF-8 output of the baseline writer, using an
explicit LF line separator. Each matching .state file is the baseline reader's
decoded state, including scalar runtime types. Both baseline and current state
were checked against the populated source model; default-valued target fields
cannot make a missing field look like a successful decode.

Covered inputs
==============
scalars: all eight primitive types; Long/Double/Boolean/Character wrapper fields;
  Byte/Short/Integer/Float inside typed collections (their direct boxed fields
  fail in the baseline); enum and explicit null over a non-null default.
containers: typed lists, nested list/map/POJO combinations and Integer map keys.
names: global snake_case, COBOL field override, canonical alias-annotated field,
  numeric name segments, final and transient fields.
comments: class/field comments, same-line YAML comment and @NewLine.
serializers: registered scalar/container conversion and annotated conversion.
placeholders: field/list registrations, verified replacements after reading.
styles: single/double quoting, literal/folded block strings without trailing LF.
json-text/json-number/json-null: baseline-supported single-field JSON objects.

Forward-compatibility assertions
================================
All ten baseline documents decode to identical populated state in current code.
Eight documents are emitted byte-for-byte unchanged by the current writer.
Two intentional format fixes have exact, narrow expectations in currentWire():
  scalars.yml: Character Z/x values are now double quoted.
  styles.yml: literal/folded blocks use explicit indentation markers |-2/>-2.
There is no general whitespace normalization or scalar-type coercion in these
comparisons. The baseline reader accepts all ten current documents with the same
decoded state. Chomping precedes indentation in |-2/>-2 so the legacy reader can
read these valid markers as well.

Limitations
===========
This corpus proves representative compatibility, not compatibility for every
application. Original multi-field JSON reading, direct Byte/Short/Integer/Float
fields, multi-stage serializer reading and trailing-newline folded strings were
already broken in the baseline. Their repaired behavior is intentionally not
frozen as a golden expectation. Untyped Object scalars and several malformed
inputs have intentional behavior changes documented in the upgrade report.
The current artifact requires Java 21; this fixture test does not promise that
it runs on the baseline README's advertised Java 8 runtime.

Reproduction
============
Compile this repository's LegacyCompatibilityRegressionTest.java against the
baseline JAR and JUnit API (annotation only), using javac --release 17 -d client.
Run net.elytrium.serializer.LegacyCompatibilityRegressionTest with arguments:
  old-output                             (baseline library on classpath)
  current-output old-output              (current library on classpath)
  reverse-output current-output          (baseline library on classpath)
All three commands check all ten cases. Each reader has a 100000-readRaw-operation cap so a
legacy parsing loop fails instead of hanging. Do not regenerate golden files
using the current library.
