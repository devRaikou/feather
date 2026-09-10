# Jenkins deployment

The public `devRaikou/feather` repository builds from `develop`. It needs its own
Pipeline job because the existing Jenkins organization folder only discovers
`survivalgam-es` repositories.

## One-time setup

1. Push `Jenkinsfile` and `ci/maven-settings.xml` to `develop`.
2. Append the job entry from `ci/jenkins-job.yaml` to the existing `jobs` list in
   `/srv/sghq/ci/config/jenkins.yaml`. Validate and reload Configuration as Code.
   Preserve the existing organization folder and credentials.
3. Enable the Feather artifact sync entry in Gate's `configs/app.yaml` and reload
   Gate configuration before starting a rollout:

   ```yaml
   - name: "Feather"
     sourcePath: "/srv/sghq/ci/staging/global"
     pattern: "Feather.jar"
     destination: "./artifacts/global/Feather.jar"
     required: false
   ```

4. Start the first build manually with `ROLLOUT_TARGET=build` to verify the map
   import fix on Build. Check the Jenkins test results, Gate rollout result, and
   Build startup logs. Use `all` when ready to update the remaining backends.

After the first build, Jenkins polls Git every five minutes and builds new
`develop` commits, updating only Build by default. Select `all` manually to roll
out the other backends too. Anonymous checkout requires no GitHub App access or webhook.
The agent needs Java 21, Maven, Bash, `gate-rollout`, `NEXUS_BASE_URL`, and the
existing `nexus-deploy` credential. Credentials are supplied by Jenkins at runtime.
Nexus supplies the Swift-compatible Spigot server dependency; the POM repositories
remain available for legacy dependencies.

The pipeline runs `clean verify`, including the serialization regression tests
with a 128 MiB test heap, then atomically stages the shaded plugin at
`/srv/sghq/ci/staging/global/Feather.jar`. Gate copies this jar into its shared
artifacts and rebuilds the selected server images during rollout. `all` restarts
Build, Limbo, Hub, SG, UHC, and Practice through Gate; `none` stages the jar without
requesting a restart. Any later Gate build can consume a staged jar.
