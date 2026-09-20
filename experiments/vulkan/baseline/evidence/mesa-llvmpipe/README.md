# Mesa software-renderer results

All eight option combinations passed back-buffer repeatability and callback-count
checks, including box/oval color landmarks, on 2026-09-20. Each produced 32 samples and two identical, nonuniform
640 × 480 back-buffer captures. All front-buffer screenshots were uniform black;
front-buffer screenshot behavior remains unverified. See `summary.csv` for counts
and each scenario's `environment.properties` for renderer details.

Reproduction from the implementation worktree:

```sh
./gradlew jar
xvfb-run -a env LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null \
  experiments/vulkan/baseline/run.sh build/libs/Tanks-1.6.2a-02c3b3f2.jar \
  experiments/vulkan/baseline-run-landmarks
```

Use the actual built JAR filename recorded in `inputs.sha256` if it differs.
`revision.txt` identifies the underlying game revision; `inputs.sha256` records
the exact game JAR and fixture source used. Generated user profiles, class files,
and native audio warnings are omitted. The run emitted an OpenAL RTKit warning
under the null audio driver; rendering completed. These are software-renderer
measurements and do not establish hardware performance or full gameplay parity.
