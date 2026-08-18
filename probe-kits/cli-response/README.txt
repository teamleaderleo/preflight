Preflight CLI response probe
============================

Purpose
-------

This probe records wall time for the human-facing commands that should feel immediate from a
terminal. Each sample launches a fresh Preflight process and captures stdout/stderr through pipes,
which also exercises redirected-output behavior.

The default cases are:

  help
    preflight --help

  doctor
    preflight doctor

  profile-list / profile-list-json
    preflight profile list [--json]

  cache-summary / cache-summary-json
    preflight cache [--json]

  prepare-plan / prepare-plan-json
    preflight prepare --plan [--json]

  cache-repair-preview / cache-repair-preview-json
    preflight cache repair [--json]

  cache-prune-preview / cache-prune-preview-json
    preflight cache prune [--json]

  uninstall-preview / uninstall-preview-json
    preflight uninstall [--json]

Pass --profile-preview NAME to add preview-only `profile activate` measurements for an existing
named profile. The probe never supplies --yes.

What it records
---------------

For every case the report records:

  * firstProcessMs: the first fresh process in this probe invocation;
  * repeatMedianMs and repeatP95Ms: the subsequent fresh-process runs;
  * min/max wall time;
  * exit codes and timeout state;
  * stdout/stderr byte counts;
  * whether either redirected stream contains an ANSI escape sequence;
  * for JSON cases, whether stdout is exactly one parseable JSON document.

"First process" is deliberately literal. OS filesystem caches, JVM/runtime caches outside the
process, antivirus state, and machine load can all affect it. For a machine-cold observation, run
the probe as the first Preflight activity after the machine reaches the state you want to measure.
Keep the generated JSON alongside the machine/runtime identity used for the run.

Build and run
-------------

Build the packaged CLI first:

  ./mvnw --batch-mode --no-transfer-progress -pl preflight-cli -am package

Measure the packaged jar with an explicit installation so discovery variability does not hide CLI
work:

  python3 probe-kits/cli-response/cli_response_probe.py \
    --jar preflight-cli/target/preflight.jar \
    --game "/path/to/Starsector" \
    --rounds 7 \
    --json \
    --output cli-response.json

An installed command can be measured instead:

  python3 probe-kits/cli-response/cli_response_probe.py \
    --preflight /path/to/preflight \
    --game "/path/to/Starsector" \
    --rounds 7

If launcher discovery is part of the local installation, add:

  --launcher "/path/to/launcher"

Measure a smaller set while iterating:

  python3 probe-kits/cli-response/cli_response_probe.py \
    --jar preflight-cli/target/preflight.jar \
    --game "/path/to/Starsector" \
    --case help \
    --case doctor \
    --case cache-summary-json \
    --rounds 7

NO_COLOR and redirected output
------------------------------

stdout and stderr are always redirected by this probe. That makes any ANSI decoration in the
captured streams a failure signal worth investigating. Run the same baseline again with:

  --set-no-color

The JSON report records whether NO_COLOR was present in child-process environment. Comparing the
two runs makes it easy to distinguish TTY detection from NO_COLOR handling once semantic terminal
decoration is introduced.

JSON cases
----------

For cases that advertise --json, stdout must parse as one JSON document. Human progress, hints,
ANSI sequences, paths, or summaries mixed into stdout will make jsonValid false. stderr remains
separately recorded so a command can keep machine stdout clean while still reporting a process-level
failure where its existing contract permits that.

Preview safety
--------------

The mutation-related cases are preview invocations only. The probe never passes --yes. A nonzero
exit code can be a valid refusal on a machine whose installation/profile cannot be identified; the
probe records the code instead of treating it as a measurement harness failure.

Self-test
---------

The probe has no third-party Python dependencies:

  python3 probe-kits/cli-response/test_cli_response_probe.py

The self-test checks the canonical case set, argument scoping, fresh-process timing, redirected ANSI
detection, and exact JSON-document validation.
