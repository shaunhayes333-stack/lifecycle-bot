#!/usr/bin/env bash
# Compile ACTUAL pure Cortex production models; no Android, wallet, RPC or sender.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
apk_dir="$(cd -- "$script_dir/../.." && pwd)"
deps_dir="${CORTEX_JVM_DEPENDENCIES:-${TMPDIR:-/tmp}/aate-cortex-jvm-dependencies-1.9.24}"
output_dir=""
fetch=false
while (($#)); do
  case "$1" in
    --fetch) fetch=true; shift ;;
    --deps-dir) deps_dir="$2"; shift 2 ;;
    --output-dir) output_dir="$2"; shift 2 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
# Pinned coordinates from the official compiler POM and app/build.gradle.kts.
artifacts=(
  org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.24/kotlin-compiler-embeddable-1.9.24.jar
  org/jetbrains/kotlin/kotlin-stdlib/1.9.24/kotlin-stdlib-1.9.24.jar
  org/jetbrains/kotlin/kotlin-script-runtime/1.9.24/kotlin-script-runtime-1.9.24.jar
  org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar
  org/jetbrains/kotlin/kotlin-daemon-embeddable/1.9.24/kotlin-daemon-embeddable-1.9.24.jar
  org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar
  org/jetbrains/annotations/13.0/annotations-13.0.jar
  junit/junit/4.13.2/junit-4.13.2.jar
  org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar
  org/json/json/20250517/json-20250517.jar
)
for artifact in "${artifacts[@]}"; do
  target="$deps_dir/${artifact##*/}"
  if [[ ! -s "$target" ]]; then
    if [[ "$fetch" != true ]]; then
      printf 'Missing dependency: %s\nUse --fetch once with approved network access, or supply --deps-dir.\n' "$target" >&2
      exit 2
    fi
    mkdir -p -- "$deps_dir"
    temporary="$(mktemp "$deps_dir/.download.XXXXXX")"
    printf 'Fetching %s\n' "${artifact##*/}"
    curl --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 300 \
      "https://repo.maven.apache.org/maven2/$artifact" --output "$temporary"
    unzip -tq "$temporary" >/dev/null
    mv -- "$temporary" "$target"
  fi
done
if [[ -z "$output_dir" ]]; then
  output_dir="$(mktemp -d "${TMPDIR:-/tmp}/aate-cortex-jvm.XXXXXX")"
else
  mkdir -p -- "$output_dir"
fi
sources="$apk_dir/app/src/main/kotlin/com/lifecyclebot/engine"
tests="$apk_dir/app/src/test/kotlin/com/lifecyclebot/engine"
classpath="$deps_dir/kotlin-stdlib-1.9.24.jar:$deps_dir/annotations-13.0.jar:$deps_dir/json-20250517.jar:$deps_dir/junit-4.13.2.jar:$deps_dir/hamcrest-core-1.3.jar"
production=(
  "$sources/truth/RobustLabelRead8185.kt"
  "$sources/cortex/CortexLedger7885.kt"
  "$sources/cortex/CortexScoreboard7885.kt"
  "$sources/cortex/CortexCalibration7901.kt"
  "$sources/cortex/CortexAuthority8366.kt"
  "$sources/cortex/CortexTailEv8324.kt"
  "$sources/cortex/CortexRunOdds8325.kt"
  "$sources/cortex/CortexWalkForward8144.kt"
  "$sources/cortex/CortexLabelHygiene8144.kt"
  "$sources/cortex/CortexGradeBook8144.kt"
  "$sources/cortex/LaneEvidence8077.kt"
  "$sources/cortex/LaneRealisedGovernor8295.kt"
  "$sources/truth/ShadowPersist8355.kt"
  "$sources/NativeDecisionEnrollment8372.kt"
  "$sources/ScannerSourceEpisodes8372.kt"
  "$sources/cortex/CortexOutcomeBook8372.kt"
  "$sources/cortex/CortexTraceArchive8372.kt"
  "$sources/cortex/CrossAssetLearningSeal8372.kt"
  "$sources/ScannerSourceBrain.kt"
  "$sources/cortex/CortexAssessmentProvenance8372.kt"
  "$sources/cortex/LaneSetupEpisodes8372.kt"
  "$sources/cortex/LanePlaybookCheckpoint8372.kt"
  "$sources/cortex/FirstSight8026.kt"
  "$sources/cortex/LearningEntryTruth8153.kt"
  "$sources/cortex/LearningObjective8153.kt"
  "$sources/cortex/CortexRealisedAuthority8136.kt"
)
printf 'Compiling %s production files; outputs: %s\n' "${#production[@]}" "$output_dir"
java -Xmx1536m -cp "$deps_dir/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -no-stdlib -no-reflect -classpath "$classpath" -d "$output_dir/production.jar" \
  "${production[@]}" "$script_dir/ServiceAdapters.kt" "$script_dir/RunnerAdapter.kt" \
  "$script_dir/AndroidPreferenceAdapters.kt" "$script_dir/ScannerAdapters.kt" \
  "$script_dir/ScannerHunterAdapter.kt" "$script_dir/FirstSightModelAdapters.kt" \
  "$script_dir/FirstSightEngineAdapters.kt" "$script_dir/FirstSightTruthAdapters.kt" \
  "$script_dir/LearningDisplayAdapters.kt"
printf 'Compiling tests against the separate production module (Gradle-style friend visibility).\n'
java -Xmx1536m -cp "$deps_dir/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -no-stdlib -no-reflect -classpath "$classpath:$output_dir/production.jar" \
  -Xfriend-paths="$output_dir/production.jar" -d "$output_dir/tests.jar" \
  "$script_dir/ScannerSourceBrainIntegrationTest.kt" \
  "$tests/Aate8076CortexRevisionTest.kt" "$tests/Aate8372CortexLearningTest.kt" \
  "$tests/Aate8372CortexCalibrationTest.kt" "$tests/Aate8372ShadowLearningTest.kt" \
  "$tests/Aate8372NativeEnrollmentTest.kt" "$tests/Aate8372SourceEpisodeTest.kt" \
  "$tests/Aate8372CortexOutcomeBookTest.kt" \
  "$tests/Aate8372CortexAuthorityTest.kt" "$tests/Aate8372CortexTraceArchiveTest.kt" \
  "$tests/Aate8372CrossAssetLearningSealTest.kt" "$tests/Aate8372CortexAssessmentProvenanceTest.kt" \
  "$tests/Aate8372LanePlaybookEpisodeTest.kt" "$tests/Aate8372FirstSightTicketTest.kt" \
  "$tests/Aate8372CortexVerdictVersionTest.kt"
printf 'Running pure-model behavior tests (not Android/service integration).\n'
cd -- "$apk_dir/app"
java -cp "$output_dir/tests.jar:$output_dir/production.jar:$classpath" org.junit.runner.JUnitCore \
  com.lifecyclebot.engine.Aate8076CortexRevisionTest \
  com.lifecyclebot.engine.Aate8372CortexLearningTest \
  com.lifecyclebot.engine.Aate8372CortexCalibrationTest \
  com.lifecyclebot.engine.Aate8372ShadowLearningTest \
  com.lifecyclebot.engine.Aate8372NativeEnrollmentTest \
  com.lifecyclebot.engine.Aate8372SourceEpisodeTest \
  com.lifecyclebot.engine.Aate8372CortexOutcomeBookTest \
  com.lifecyclebot.engine.Aate8372CortexAuthorityTest \
  com.lifecyclebot.engine.Aate8372CortexTraceArchiveTest \
  com.lifecyclebot.engine.Aate8372CrossAssetLearningSealTest \
  com.lifecyclebot.engine.Aate8372CortexAssessmentProvenanceTest \
  com.lifecyclebot.engine.Aate8372LanePlaybookEpisodeTest \
  com.lifecyclebot.engine.Aate8372FirstSightTicketTest \
  com.lifecyclebot.engine.Aate8372CortexVerdictVersionTest \
  com.lifecyclebot.engine.ScannerSourceBrainIntegrationTest
