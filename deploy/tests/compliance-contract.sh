#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
export TZ=UTC
umask 077

test_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(CDPATH='' cd -- "$test_dir/../.." && pwd -P)"
tmp="$(mktemp -d)"
cleanup() {
  rm -rf --one-file-system -- "$tmp"
}
trap cleanup EXIT

fail() {
  printf 'compliance-contract: FAIL: %s\n' "$*" >&2
  exit 1
}

expect_failure() {
  if "$@" >"$tmp/expected-failure.stdout" 2>"$tmp/expected-failure.stderr"; then
    fail "command unexpectedly succeeded: $*"
  fi
}

for command_name in cmp cp cut diff find grep kill ln mktemp node python3 realpath rm setsid sha256sum sleep; do
  command -v "$command_name" >/dev/null 2>&1 || fail "missing test command: $command_name"
done

node - "$repo_root" <<'NODE'
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const root = process.argv[2]
const expected = {
  CYCLONEDX_SPEC_VERSION: '1.6',
  CYCLONEDX_NPM_VERSION: '6.0.0',
  CYCLONEDX_NPM_INTEGRITY:
    'sha512-kpWjjV0j5y0mMHUB5dSx1hxweH8K2blSqkgdQ6eHgU7aClB4CcXGhbHtGY6WVHSo3A01Rt7WOLas/wQ1E+tBDg==',
  CYCLONEDX_VALIDATOR_AJV_VERSION: '8.20.0',
  CYCLONEDX_VALIDATOR_FORMATS_VERSION: '3.0.1',
  CYCLONEDX_VALIDATOR_DRAFT2019_VERSION: '1.6.1',
  CYCLONEDX_MAVEN_PLUGIN_VERSION: '2.9.2',
  MAVEN_DEPENDENCY_PLUGIN_VERSION: '3.11.0',
  SYFT_PLATFORM: 'linux/amd64',
  SYFT_IMAGE:
    'docker.io/anchore/syft:v1.44.0@sha256:2baa4d24d90599840c0100a8d30deaa533821fcd99f405ce6f90e3d225bd836d',
}
const toolchain = Object.fromEntries(
  fs
    .readFileSync(path.join(root, 'deploy/compliance/toolchain.env'), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => {
      const separator = line.indexOf('=')
      if (separator <= 0) throw new Error(`invalid toolchain line: ${line}`)
      return [line.slice(0, separator), line.slice(separator + 1)]
    }),
)
if (JSON.stringify(toolchain) !== JSON.stringify(expected)) throw new Error('toolchain pins changed without contract review')

const overrides = JSON.parse(fs.readFileSync(path.join(root, 'deploy/compliance/license-overrides.json'), 'utf8'))
const expectedNodeOverrides = {
  '@vue/devtools-api@6.6.4': {
    components: ['admin-web'],
    license: 'MIT',
    file: 'license-texts/vue-devtools-api-6.6.4-LICENSE.txt',
    sha256: '050bbca6960784db52ff387271bf2ecc5cbed7cf8581b415d528a6ecb6585015',
    source:
      'https://raw.githubusercontent.com/vuejs/vue-devtools/df6ab6bb7791a7a525a97990de73b3ea5e9a1941/LICENSE',
  },
  'agent-base@6.0.2': {
    components: ['admin-web'],
    license: 'MIT',
    file: 'license-texts/nathan-rajlich-mit-LICENSE.txt',
    sha256: '71368fd0f5b4129191e9afcd1e1ef2dc89a9090d3e4d80bbab92dafd032b3bef',
    source:
      'https://raw.githubusercontent.com/TooTallNate/node-agent-base/c4b8ea2e1a11bae023bb09b708050a50418204e9/README.md#license',
  },
  'https-proxy-agent@5.0.1': {
    components: ['admin-web'],
    license: 'MIT',
    file: 'license-texts/nathan-rajlich-mit-LICENSE.txt',
    sha256: '71368fd0f5b4129191e9afcd1e1ef2dc89a9090d3e4d80bbab92dafd032b3bef',
    source:
      'https://raw.githubusercontent.com/TooTallNate/node-https-proxy-agent/d0d80cc0482f20495aa8595f802e1a9f3b1b3409/README.md#license',
  },
}
const expectedMavenOverrides = {
  'com.qcloud:cos_api:5.6.227': {
    declaredLicense: 'cos-java-sdk',
    license: 'MIT',
    file: 'license-texts/tencent-cos-java-sdk-v5-5.6.227-LICENSE.txt',
    sha256: '33992d01958ef4f7cc2fc12fa06c5c74d8950f43de9dd68aa69d4137c4c96ae8',
    source:
      'https://raw.githubusercontent.com/tencentyun/cos-java-sdk-v5/e3e356d9ba57dad8504d0eaf12c105572faeae70/LICENSE',
  },
  'org.bouncycastle:bcprov-jdk18on:1.84': {
    declaredLicense: 'Bouncy Castle Licence',
    license: 'MIT',
    file: 'license-texts/bouncycastle-1.84-LICENSE.html',
    sha256: 'edbbb10380b1271998b867a2e36b1cbee226e03d438726e1a91f80c5dde11849',
    source: 'https://raw.githubusercontent.com/bcgit/bc-java/d716d7716a452bad283323aefd88ff21eba8deef/LICENSE.html',
  },
  'xmlpull:xmlpull:1.1.3.1': {
    declaredLicense: 'Public Domain',
    license: 'LicenseRef-Public-Domain',
    file: 'license-texts/xmlpull-1.1.3.1-LICENSE.txt',
    sha256: '4eeafa29d1d9a0cdbdad0f5178cd553527329b57b5808ffe912cb9c698343612',
    source:
      'https://raw.githubusercontent.com/xmlpull-org/xmlpull-api-v1/21127efba74f6f147e867e2bcf3e8b2a9624cc2c/LICENSE.txt',
  },
}
if (JSON.stringify(Object.keys(overrides)) !== JSON.stringify(['schemaVersion', 'nodeComponents', 'packages', 'mavenPackages'])) {
  throw new Error('license override manifest fields changed without contract review')
}
if (JSON.stringify(overrides.nodeComponents) !== JSON.stringify(['frontend', 'admin-web'])) {
  throw new Error('Node override component scope changed without contract review')
}
if (JSON.stringify(overrides.packages) !== JSON.stringify(expectedNodeOverrides)) {
  throw new Error('Node reviewed license overrides changed without contract review')
}
if (JSON.stringify(overrides.mavenPackages) !== JSON.stringify(expectedMavenOverrides)) {
  throw new Error('Maven reviewed license overrides changed without contract review')
}
for (const override of [...Object.values(expectedNodeOverrides), ...Object.values(expectedMavenOverrides)]) {
  const bytes = fs.readFileSync(path.join(root, 'deploy/compliance', ...override.file.split('/')))
  const actual = crypto.createHash('sha256').update(bytes).digest('hex')
  if (actual !== override.sha256) throw new Error(`reviewed license digest changed: ${override.file}`)
}
const assetsPath = path.join(root, 'deploy/compliance/assets.json')
const assetsBytes = fs.readFileSync(assetsPath)
if (crypto.createHash('sha256').update(assetsBytes).digest('hex') !== 'bd6363164b440d7d69d5f8eb6076ea7fb8781c7df0a36edb353910f3f315b5e2') {
  throw new Error('reviewed asset provenance manifest changed without contract review')
}
const assets = JSON.parse(assetsBytes)
if (
  assets.schemaVersion !== 2 ||
  assets.assets?.length !== 5 ||
  assets.licenseNotices?.['LicenseRef-Unsplash']?.url !== 'https://unsplash.com/license' ||
  assets.licenseNotices?.['LicenseRef-Pexels']?.url !== 'https://www.pexels.com/license/'
) {
  throw new Error('asset provenance license/source contract changed')
}
for (const asset of assets.assets) {
  if (['LicenseRef-Unsplash', 'LicenseRef-Pexels'].includes(asset.license) && (!asset.credit || !asset.sourceUrl)) {
    throw new Error(`third-party asset has incomplete credit/source provenance: ${asset.path}`)
  }
}
for (const [relativePath, expectedSha256] of [
  ['deploy/backup/requirements-cos-prune.txt', '96d0cf7823a4f32f2fbb97e94919882827fd8aa9cd153f8395ef26459a19ff4a'],
  ['deploy/compliance/python-legal-overrides.json', 'dc2ecb919d93707e6451aed73afc5f79966ab7233e7a6ba8432ac6a1d782c1fc'],
  ['deploy/compliance/oci-legal-overrides.json', '33b77c677026235e25e028ede34ce7b2a4db2689d27812ce952464f584db2081'],
]) {
  const actual = crypto.createHash('sha256').update(fs.readFileSync(path.join(root, ...relativePath.split('/')))).digest('hex')
  if (actual !== expectedSha256) throw new Error('reviewed compliance input changed: ' + relativePath)
}
const pythonOverrides = JSON.parse(
  fs.readFileSync(path.join(root, 'deploy/compliance/python-legal-overrides.json'), 'utf8'),
)
if (
  JSON.stringify(Object.keys(pythonOverrides)) !== JSON.stringify(['schemaVersion', 'packages']) ||
  pythonOverrides.schemaVersion !== 1 ||
  JSON.stringify(Object.keys(pythonOverrides.packages)) !==
    JSON.stringify([
      'crcmod==1.7',
      'tencentcloud-sdk-python-common==3.1.135',
      'tencentcloud-sdk-python-sts==3.0.1459',
    ])
) {
  throw new Error('Python reviewed legal override set changed')
}
for (const override of Object.values(pythonOverrides.packages)) {
  for (const legal of override.files) {
    const bytes = fs.readFileSync(path.join(root, 'deploy/compliance', ...legal.file.split('/')))
    if (crypto.createHash('sha256').update(bytes).digest('hex') !== legal.sha256) {
      throw new Error('Python reviewed legal text changed: ' + legal.file)
    }
  }
}
const ociOverrides = JSON.parse(
  fs.readFileSync(path.join(root, 'deploy/compliance/oci-legal-overrides.json'), 'utf8'),
)
const expectedOciPackages = [
  'pkg:golang/github.com/moby/sys/user@v0.1.0',
  'pkg:golang/github.com/tianon/gosu@v1.19.0',
  'pkg:golang/golang.org/x/sys@v0.1.0',
  'pkg:golang/stdlib@1.24.6',
]
if (
  JSON.stringify(Object.keys(ociOverrides)) !== JSON.stringify(['schemaVersion', 'postgres']) ||
  ociOverrides.schemaVersion !== 1 ||
  JSON.stringify(Object.keys(ociOverrides.postgres).sort()) !== JSON.stringify(['packages', 'scripts']) ||
  JSON.stringify(Object.keys(ociOverrides.postgres.packages)) !== JSON.stringify(expectedOciPackages) ||
  JSON.stringify(ociOverrides.postgres.scripts.map((entry) => entry.path)) !==
    JSON.stringify(['/usr/local/bin/docker-entrypoint.sh', '/usr/local/bin/docker-ensure-initdb.sh'])
) {
  throw new Error('OCI reviewed legal override set changed')
}
for (const override of [
  ...Object.values(ociOverrides.postgres.packages),
  ...ociOverrides.postgres.scripts,
]) {
  const bytes = fs.readFileSync(path.join(root, 'deploy/compliance', ...override.licenseFile.split('/')))
  if (crypto.createHash('sha256').update(bytes).digest('hex') !== override.licenseSha256) {
    throw new Error('OCI reviewed legal text changed: ' + override.licenseFile)
  }
}

const sbomCommand =
  'cyclonedx-npm --package-lock-only --omit dev --spec-version 1.6 --output-reproducible --output-format JSON --validate'
for (const project of ['frontend', 'admin-web']) {
  const pkg = JSON.parse(fs.readFileSync(path.join(root, project, 'package.json'), 'utf8'))
  const lock = JSON.parse(fs.readFileSync(path.join(root, project, 'package-lock.json'), 'utf8'))
  const tool = lock.packages?.['node_modules/@cyclonedx/cyclonedx-npm']
  if (pkg.devDependencies?.['@cyclonedx/cyclonedx-npm'] !== expected.CYCLONEDX_NPM_VERSION) {
    throw new Error(`${project}: CycloneDX npm is not exact-pinned`)
  }
  if (
    lock.packages?.['']?.devDependencies?.['@cyclonedx/cyclonedx-npm'] !== expected.CYCLONEDX_NPM_VERSION ||
    tool?.version !== expected.CYCLONEDX_NPM_VERSION ||
    tool?.integrity !== expected.CYCLONEDX_NPM_INTEGRITY ||
    tool?.dev !== true
  ) {
    throw new Error(`${project}: lockfile CycloneDX tool contract failed`)
  }
  for (const [name, version] of Object.entries({
    ajv: expected.CYCLONEDX_VALIDATOR_AJV_VERSION,
    'ajv-formats': expected.CYCLONEDX_VALIDATOR_FORMATS_VERSION,
    'ajv-formats-draft2019': expected.CYCLONEDX_VALIDATOR_DRAFT2019_VERSION,
  })) {
    if (
      pkg.devDependencies?.[name] !== version ||
      lock.packages?.['']?.devDependencies?.[name] !== version ||
      lock.packages?.[`node_modules/${name}`]?.version !== version ||
      lock.packages?.[`node_modules/${name}`]?.dev !== true
    ) {
      throw new Error(`${project}: CycloneDX validator ${name} is not directly exact-pinned`)
    }
  }
  if (pkg.scripts?.['sbom:prod'] !== sbomCommand) throw new Error(`${project}: production SBOM command changed`)
}

const pom = fs.readFileSync(path.join(root, 'backend-parent/pom.xml'), 'utf8')
for (const fragment of [
  '<project.build.outputTimestamp>2000-01-01T00:00:00Z</project.build.outputTimestamp>',
  '<cyclonedx-maven.version>2.9.2</cyclonedx-maven.version>',
  '<artifactId>cyclonedx-maven-plugin</artifactId>',
  '<goal>makeAggregateBom</goal>',
  '<schemaVersion>1.6</schemaVersion>',
  '<includeCompileScope>true</includeCompileScope>',
  '<includeRuntimeScope>true</includeRuntimeScope>',
  '<includeTestScope>false</includeTestScope>',
  '<outputReactorProjects>false</outputReactorProjects>',
]) {
  if (!pom.includes(fragment)) throw new Error(`backend POM missing: ${fragment}`)
}
const generator = fs.readFileSync(path.join(root, 'deploy/scripts/generate-compliance-artifacts.sh'), 'utf8')
for (const fragment of [
  'maven-dependency-plugin:$MAVEN_DEPENDENCY_PLUGIN_VERSION:copy-dependencies',
  '-DincludeScope=runtime',
  '-DexcludeGroupIds=xyz.yychainsaw',
  'pkg:maven/xyz.yychainsaw/portfolio-pojo@',
  'pkg:maven/xyz.yychainsaw/portfolio-common@',
  'pkg:maven/xyz.yychainsaw/portfolio-server@',
  'pkg:maven/org.springframework.boot/spring-boot-starter-web@',
  'pkg:maven/org.postgresql/postgresql@',
  'pkg:maven/com.qcloud/cos_api@',
  String.raw`org\\.testcontainers|org\\.junit|junit`,
]) {
  if (!generator.includes(fragment)) throw new Error(`generator missing production-closure assertion: ${fragment}`)
}
if (generator.includes('-DexcludeScope=test')) throw new Error('conflicting Maven scope filters returned')
NODE

for script in \
  deploy/scripts/generate-compliance-artifacts.sh \
  deploy/scripts/generate-oci-license-closure.sh \
  deploy/scripts/run-syft-sbom.sh \
  deploy/scripts/finalize-compliance-tree.sh; do
  bash -n "$repo_root/$script"
  grep -Fq "trap 'exit 129' HUP" "$repo_root/$script" || fail "$script does not exit on HUP"
  grep -Fq "trap 'exit 130' INT" "$repo_root/$script" || fail "$script does not exit on INT"
  grep -Fq "trap 'exit 143' TERM" "$repo_root/$script" || fail "$script does not exit on TERM"
done
for script in \
  deploy/scripts/collect-node-licenses.mjs \
  deploy/scripts/node-production-closure.mjs \
  deploy/scripts/reconcile-node-sbom.mjs \
  deploy/scripts/collect-maven-licenses.mjs \
  deploy/scripts/collect-oci-image-licenses.mjs \
  deploy/scripts/normalize-cyclonedx.mjs \
  deploy/scripts/render-asset-provenance.mjs \
  deploy/scripts/verify-compliance-tree.mjs; do
  node --check "$repo_root/$script"
done
PYTHONPYCACHEPREFIX="$tmp/pycache" python3 -m py_compile \
  "$repo_root/deploy/scripts/collect-python-licenses.py" \
  "$repo_root/deploy/scripts/extract-docker-image-rootfs.py"

# Required source directory parents and every reviewed leaf must remain inside the canonical source root.
escaping_source="$tmp/escaping-source"
mkdir -p -- "$escaping_source/admin-web" "$escaping_source/backend-parent" "$escaping_source/deploy" "$tmp/outside-frontend"
ln -s -- "$tmp/outside-frontend" "$escaping_source/frontend"
expect_failure bash "$repo_root/deploy/scripts/generate-compliance-artifacts.sh" \
  --source "$escaping_source" --output "$tmp/escaping-output"

# The canonical lock traversal handles nested duplicate identities, dev/optional/peer edges, and Linux/x64 filters.
node_project="$tmp/node-project"
mkdir -p -- \
  "$node_project/node_modules/prod-a" \
  "$node_project/node_modules/prod-b/node_modules/shared" \
  "$node_project/node_modules/shared" \
  "$node_project/node_modules/linux-only" \
  "$node_project/node_modules/dev-only" \
  "$tmp/node-compliance"
cat >"$node_project/package.json" <<'JSON'
{"name":"contract-project","version":"1.0.0","dependencies":{"prod-a":"1.0.0","prod-b":"1.0.0"},"devDependencies":{"dev-only":"1.0.0"}}
JSON
cat >"$node_project/package-lock.json" <<'JSON'
{
  "name":"contract-project",
  "version":"1.0.0",
  "lockfileVersion":3,
  "packages":{
    "":{"name":"contract-project","version":"1.0.0","dependencies":{"prod-a":"1.0.0","prod-b":"1.0.0"},"devDependencies":{"dev-only":"1.0.0"}},
    "node_modules/prod-a":{"version":"1.0.0","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","dependencies":{"shared":"1.0.0"},"optionalDependencies":{"linux-only":"1.0.0","win-only":"1.0.0"},"peerDependencies":{"dev-only":"1.0.0"},"peerDependenciesMeta":{"dev-only":{"optional":true}}},
    "node_modules/prod-b":{"version":"1.0.0","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","dependencies":{"shared":"1.0.0"}},
    "node_modules/shared":{"version":"1.0.0","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="},
    "node_modules/prod-b/node_modules/shared":{"version":"1.0.0","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="},
    "node_modules/linux-only":{"version":"1.0.0","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","optional":true,"os":["linux"],"cpu":["x64"]},
    "node_modules/win-only":{"version":"1.0.0","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","optional":true,"os":["win32"]},
    "node_modules/dev-only":{"version":"1.0.0","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","dev":true}
  }
}
JSON
for package_path in prod-a prod-b shared linux-only prod-b/node_modules/shared; do
  package_name=${package_path##*/}
  cat >"$node_project/node_modules/$package_path/package.json" <<JSON
{"name":"$package_name","version":"1.0.0","license":"MIT"}
JSON
  printf 'MIT fixture license\n' >"$node_project/node_modules/$package_path/LICENSE"
done
cat >"$node_project/node_modules/dev-only/package.json" <<'JSON'
{"name":"dev-only","version":"1.0.0","license":"UNLICENSED"}
JSON
cat >"$tmp/node-compliance/license-overrides.json" <<'JSON'
{"schemaVersion":1,"nodeComponents":["fixture"],"packages":{},"mavenPackages":{}}
JSON
node "$repo_root/deploy/scripts/collect-node-licenses.mjs" \
  --project "$node_project" --component fixture --output "$tmp/node-output" \
  --overrides "$tmp/node-compliance/license-overrides.json"
cat >"$tmp/node-raw.cdx.json" <<'JSON'
{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,"metadata":{"component":{"type":"application","name":"contract-project","version":"1.0.0"}},"components":[{"type":"library","name":"prod-a","version":"1.0.0","purl":"pkg:npm/prod-a@1.0.0"},{"type":"library","name":"prod-b","version":"1.0.0","purl":"pkg:npm/prod-b@1.0.0"},{"type":"library","name":"dev-only","version":"1.0.0","purl":"pkg:npm/dev-only@1.0.0"}]}
JSON
node "$repo_root/deploy/scripts/reconcile-node-sbom.mjs" \
  --lock "$node_project/package-lock.json" \
  --raw-sbom "$tmp/node-raw.cdx.json" \
  --license-manifest "$tmp/node-output/manifest.json" \
  --output "$tmp/node-reconciled.cdx.json"
node - "$tmp/node-output/manifest.json" "$tmp/node-reconciled.cdx.json" <<'NODE'
const fs = require('fs')
const manifest = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
const sbom = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'))
const expected = ['linux-only@1.0.0', 'prod-a@1.0.0', 'prod-b@1.0.0', 'shared@1.0.0']
const legal = manifest.packages.map((entry) => `${entry.name}@${entry.version}`).sort()
const components = sbom.components.map((entry) => decodeURIComponent(entry.purl.slice(8))).sort()
if (manifest.schemaVersion !== 2 || JSON.stringify(legal) !== JSON.stringify(expected)) {
  throw new Error('canonical Node license identity closure failed')
}
if (JSON.stringify(components) !== JSON.stringify(expected)) throw new Error('SBOM/legal identity reconciliation failed')
const shared = manifest.packages.find((entry) => entry.name === 'shared')
if (shared.lockPaths.length !== 2) throw new Error('same-identity nested lock paths were not collapsed')
NODE
cat >"$node_project/node_modules/prod-a/package.json" <<'JSON'
{"name":"prod-a","version":"1.0.0"}
JSON
expect_failure node "$repo_root/deploy/scripts/collect-node-licenses.mjs" \
  --project "$node_project" --component fixture --output "$tmp/node-missing-output" \
  --overrides "$tmp/node-compliance/license-overrides.json"

# Reviewed immutable overrides are accepted only while their local text hash remains exact.
override_project="$tmp/override-project"
override_root="$tmp/override-compliance"
mkdir -p -- "$override_project/node_modules/override-pkg" "$override_root/license-texts"
cat >"$override_project/package.json" <<'JSON'
{"name":"override-contract","version":"1.0.0","dependencies":{"override-pkg":"2.0.0"}}
JSON
cat >"$override_project/package-lock.json" <<'JSON'
{"name":"override-contract","version":"1.0.0","lockfileVersion":3,"packages":{"":{"name":"override-contract","version":"1.0.0","dependencies":{"override-pkg":"2.0.0"}},"node_modules/override-pkg":{"version":"2.0.0","integrity":"sha512-CCCC"}}}
JSON
cat >"$override_project/node_modules/override-pkg/package.json" <<'JSON'
{"name":"override-pkg","version":"2.0.0","license":"MIT"}
JSON
printf 'Reviewed MIT fixture text\n' >"$override_root/license-texts/override-LICENSE.txt"
override_hash="$(sha256sum -- "$override_root/license-texts/override-LICENSE.txt" | cut -d ' ' -f 1)"
cat >"$override_root/license-overrides.json" <<JSON
{"schemaVersion":1,"nodeComponents":["override"],"packages":{"override-pkg@2.0.0":{"components":["override"],"license":"MIT","file":"license-texts/override-LICENSE.txt","sha256":"$override_hash","source":"https://raw.githubusercontent.com/example/example/0123456789abcdef0123456789abcdef01234567/LICENSE"}},"mavenPackages":{}}
JSON
node "$repo_root/deploy/scripts/collect-node-licenses.mjs" \
  --project "$override_project" --component override --output "$tmp/override-output" \
  --overrides "$override_root/license-overrides.json"
cat >"$override_root/unused-overrides.json" <<JSON
{"schemaVersion":1,"nodeComponents":["override"],"packages":{"override-pkg@2.0.0":{"components":["override"],"license":"MIT","file":"license-texts/override-LICENSE.txt","sha256":"$override_hash","source":"https://raw.githubusercontent.com/example/example/0123456789abcdef0123456789abcdef01234567/LICENSE"},"unused-pkg@1.0.0":{"components":["override"],"license":"MIT","file":"license-texts/override-LICENSE.txt","sha256":"$override_hash","source":"https://raw.githubusercontent.com/example/example/0123456789abcdef0123456789abcdef01234567/LICENSE"}},"mavenPackages":{}}
JSON
expect_failure node "$repo_root/deploy/scripts/collect-node-licenses.mjs" \
  --project "$override_project" --component override --output "$tmp/override-unused-output" \
  --overrides "$override_root/unused-overrides.json"
cat >"$override_root/unknown-field-overrides.json" <<JSON
{"schemaVersion":1,"nodeComponents":["override"],"packages":{"override-pkg@2.0.0":{"components":["override"],"license":"MIT","file":"license-texts/override-LICENSE.txt","sha256":"$override_hash","source":"https://raw.githubusercontent.com/example/example/0123456789abcdef0123456789abcdef01234567/LICENSE","note":"not reviewed"}},"mavenPackages":{}}
JSON
expect_failure node "$repo_root/deploy/scripts/collect-node-licenses.mjs" \
  --project "$override_project" --component override --output "$tmp/override-unknown-output" \
  --overrides "$override_root/unknown-field-overrides.json"
cat >"$override_root/duplicate-overrides.json" <<JSON
{"schemaVersion":1,"nodeComponents":["override"],"packages":{"override-pkg@2.0.0":{"components":["override"],"license":"MIT","license":"MIT","file":"license-texts/override-LICENSE.txt","sha256":"$override_hash","source":"https://raw.githubusercontent.com/example/example/0123456789abcdef0123456789abcdef01234567/LICENSE"}},"mavenPackages":{}}
JSON
expect_failure node "$repo_root/deploy/scripts/collect-node-licenses.mjs" \
  --project "$override_project" --component override --output "$tmp/override-duplicate-output" \
  --overrides "$override_root/duplicate-overrides.json"
printf 'tampered\n' >>"$override_root/license-texts/override-LICENSE.txt"
expect_failure node "$repo_root/deploy/scripts/collect-node-licenses.mjs" \
  --project "$override_project" --component override --output "$tmp/override-tamper-output" \
  --overrides "$override_root/license-overrides.json"

# The asset inventory is closed and content-addressed.
asset_source="$tmp/asset-source"
asset_manifest="$tmp/asset-manifest.json"
mkdir -p -- "$asset_source/frontend/public" "$asset_source/frontend/src/assets"
node - "$asset_source" "$asset_manifest" <<'NODE'
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const source = process.argv[2]
const manifestPath = process.argv[3]
const relativePath = 'frontend/public/fixture.svg'
const bytes = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>\n')
fs.writeFileSync(path.join(source, ...relativePath.split('/')), bytes)
fs.writeFileSync(
  manifestPath,
  `${JSON.stringify(
    {
      schemaVersion: 2,
      licenseDefinitions: { 'LicenseRef-Fixture': 'Fixture content.' },
      licenseNotices: {
        'LicenseRef-Fixture': {
          url: 'https://example.test/license',
          reviewedAt: '2026-07-19',
          summary: 'The contract fixture permits this test asset and records a sufficiently complete reviewed summary.',
        },
      },
      assets: [
        {
          path: relativePath,
          bytes: bytes.length,
          sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
          origin: 'Contract fixture',
          license: 'LicenseRef-Fixture',
          credit: 'Fixture author',
          sourceUrl: 'https://example.test/source',
        },
      ],
    },
    null,
    2,
  )}\n`,
)
NODE
node "$repo_root/deploy/scripts/render-asset-provenance.mjs" \
  --source "$asset_source" --manifest "$asset_manifest" --output "$tmp/asset-provenance.md"
printf 'unlisted\n' >"$asset_source/frontend/src/assets/unlisted.png"
expect_failure node "$repo_root/deploy/scripts/render-asset-provenance.mjs" \
  --source "$asset_source" --manifest "$asset_manifest" --output "$tmp/asset-unlisted.md"
rm -f -- "$asset_source/frontend/src/assets/unlisted.png"
printf 'tampered\n' >>"$asset_source/frontend/public/fixture.svg"
expect_failure node "$repo_root/deploy/scripts/render-asset-provenance.mjs" \
  --source "$asset_source" --manifest "$asset_manifest" --output "$tmp/asset-tampered.md"

# The COS prune Python legal closure is derived only from the 12 hash-locked installed distributions.
python_requirements="$tmp/python-requirements.txt"
python_site="$tmp/python-site-packages"
python_output="$tmp/python-licenses"
python_overrides="$tmp/python-overrides.json"
printf '%s\n' '{"schemaVersion":1,"packages":{}}' >"$python_overrides"
python3 - "$python_requirements" "$python_site" <<'PY'
import pathlib
import sys

requirements = pathlib.Path(sys.argv[1])
site = pathlib.Path(sys.argv[2])
site.mkdir(parents=True)
names = [
    'certifi', 'charset-normalizer', 'cos-python-sdk-v5', 'crcmod', 'idna', 'pycryptodome',
    'requests', 'six', 'tencentcloud-sdk-python-common', 'tencentcloud-sdk-python-sts',
    'urllib3', 'xmltodict',
]
lock = []
for index, name in enumerate(names, start=1):
    version = f'1.0.{index}'
    lock.append(f'{name}=={version} \\\n    --hash=sha256:{index:064x}\n')
    directory = site / f'{name.replace("-", "_")}-{version}.dist-info'
    licenses = directory / 'licenses'
    licenses.mkdir(parents=True)
    metadata = f'Metadata-Version: 2.4\nName: {name}\nVersion: {version}\nLicense-Expression: MIT\nLicense-File: LICENSE.txt\n\n'
    (directory / 'METADATA').write_text(metadata, encoding='utf-8')
    (licenses / 'LICENSE.txt').write_text(f'MIT fixture license for {name}\n', encoding='utf-8')
    prefix = directory.relative_to(site).as_posix()
    (directory / 'RECORD').write_text(
        f'{prefix}/METADATA,,\n{prefix}/licenses/LICENSE.txt,,\n{prefix}/RECORD,,\n',
        encoding='utf-8',
    )
requirements.write_text(''.join(lock), encoding='utf-8')
PY
python3 "$repo_root/deploy/scripts/collect-python-licenses.py" \
  --requirements "$python_requirements" --overrides "$python_overrides" \
  --site-packages "$python_site" --output "$python_output" --expected-count 12
python_tree="$tmp/python-tree"
mkdir -p -- "$python_tree/licenses"
cp -a -- "$python_output" "$python_tree/licenses/cos-prune-runtime"
bash "$repo_root/deploy/scripts/finalize-compliance-tree.sh" --tree "$python_tree"
node "$repo_root/deploy/scripts/verify-compliance-tree.mjs" --tree "$python_tree"
cp -- "$python_requirements" "$tmp/python-requirements.clean"
printf '\ncos-python-sdk-v5==1.0.3 \\\n    --hash=sha256:%064d\n' 0 >>"$python_requirements"
expect_failure python3 "$repo_root/deploy/scripts/collect-python-licenses.py" \
  --requirements "$python_requirements" --overrides "$python_overrides" \
  --site-packages "$python_site" --output "$tmp/python-duplicate" --expected-count 12
cp -- "$tmp/python-requirements.clean" "$python_requirements"
rm -f -- "$python_site/certifi-1.0.1.dist-info/licenses/LICENSE.txt"
expect_failure python3 "$repo_root/deploy/scripts/collect-python-licenses.py" \
  --requirements "$python_requirements" --overrides "$python_overrides" \
  --site-packages "$python_site" --output "$tmp/python-missing-legal" --expected-count 12

# Syft runs only from the exact preloaded linux/amd64 digest with Docker networking disabled.
fake_bin="$tmp/fake-bin"
fake_docker_log="$tmp/fake-docker.log"
mkdir -p -- "$fake_bin" "$tmp/syft-output"
cat >"$fake_bin/docker" <<'BASH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_DOCKER_LOG"
if [[ "${1:-}" == image && "${2:-}" == inspect ]]; then
  printf '{"Id":"sha256:fixture"}\n'
  exit 0
fi
if [[ "${1:-}" == run ]]; then
  if [[ -n "${FAKE_SYFT_SBOM:-}" ]]; then
    cat -- "$FAKE_SYFT_SBOM"
  else
    printf '%s\n' '{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,"serialNumber":"urn:uuid:00000000-0000-0000-0000-000000000000","metadata":{"timestamp":"2000-01-01T00:00:00Z","component":{"type":"container","name":"fixture-image","version":"1"}},"components":[{"type":"library","name":"fixture-package","version":"1","licenses":[{"license":{"id":"MIT"}}]}]}'
  fi
  exit 0
fi
exit 90
BASH
chmod 0700 -- "$fake_bin/docker"
printf 'fixture OCI archive\n' >"$tmp/fixture.oci.tar"
FAKE_DOCKER_LOG="$fake_docker_log" PATH="$fake_bin:$PATH" \
  bash "$repo_root/deploy/scripts/run-syft-sbom.sh" \
  --archive "$tmp/fixture.oci.tar" --output "$tmp/syft-output/fixture.cdx.json"
grep -Fq -- '--pull never' "$fake_docker_log" || fail 'Syft Docker run did not disable pulls'
grep -Fq -- '--network none' "$fake_docker_log" || fail 'Syft Docker run did not disable networking'
grep -Fq -- '--platform linux/amd64' "$fake_docker_log" || fail 'Syft Docker run platform changed'
grep -Fq -- '--read-only' "$fake_docker_log" || fail 'Syft Docker run is not read-only'
grep -Fq -- '--cap-drop ALL' "$fake_docker_log" || fail 'Syft Docker capabilities were not dropped'
grep -Fq -- '--security-opt no-new-privileges:true' "$fake_docker_log" || fail 'Syft Docker security options changed'
grep -Fq -- 'docker.io/anchore/syft:v1.44.0@sha256:2baa4d24d90599840c0100a8d30deaa533821fcd99f405ce6f90e3d225bd836d' \
  "$fake_docker_log" || fail 'Syft exact digest was not used'
if grep -Fiq -- 'latest' "$fake_docker_log"; then fail 'floating Syft tag was used'; fi
node - "$tmp/syft-output/fixture.cdx.json" <<'NODE'
const fs = require('fs')
const bom = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
if (bom.serialNumber || bom.metadata?.timestamp) throw new Error('Syft BOM was not normalized reproducibly')
NODE

# A minimal docker-save PostgreSQL image closes dpkg -> Syft -> legal evidence
# through the same offline wrapper used by release construction.
oci_fixture="$tmp/oci-fixture"
oci_source="$oci_fixture/source"
oci_layer_root="$oci_fixture/layer-root"
mkdir -p -- \
  "$oci_source/deploy/scripts" "$oci_source/deploy/compliance" \
  "$oci_layer_root/var/lib/dpkg" "$oci_layer_root/usr/share/doc/fixture" \
  "$oci_fixture/output-parent"
for helper in \
  generate-oci-license-closure.sh extract-docker-image-rootfs.py \
  run-syft-sbom.sh collect-oci-image-licenses.mjs node-production-closure.mjs \
  normalize-cyclonedx.mjs finalize-compliance-tree.sh verify-compliance-tree.mjs; do
  cp -- "$repo_root/deploy/scripts/$helper" "$oci_source/deploy/scripts/$helper"
done
cp -- "$repo_root/deploy/compliance/toolchain.env" "$oci_source/deploy/compliance/toolchain.env"
printf '%s\n' '{"schemaVersion":1,"postgres":{"packages":{},"scripts":[]}}' \
  >"$oci_source/deploy/compliance/oci-legal-overrides.json"
cat >"$oci_layer_root/var/lib/dpkg/status" <<'STATUS'
Package: fixture
Status: install ok installed
Architecture: amd64
Version: 1.0

STATUS
printf 'MIT fixture operating-system package license\n' \
  >"$oci_layer_root/usr/share/doc/fixture/copyright"
tar --sort=name --format=ustar --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -cf "$oci_fixture/layer.tar" -C "$oci_layer_root" var usr
layer_sha="$(sha256sum -- "$oci_fixture/layer.tar" | awk '{print $1}')"
node - "$oci_fixture/config.body" "$layer_sha" <<'NODE'
const fs = require('fs')
const [output, layerSha] = process.argv.slice(2)
fs.writeFileSync(output, `${JSON.stringify({
  architecture: 'amd64',
  os: 'linux',
  rootfs: { type: 'layers', diff_ids: [`sha256:${layerSha}`] },
})}\n`)
NODE
config_sha="$(sha256sum -- "$oci_fixture/config.body" | awk '{print $1}')"
mv -- "$oci_fixture/config.body" "$oci_fixture/$config_sha.json"
node - "$oci_fixture/manifest.json" "$config_sha" <<'NODE'
const fs = require('fs')
const [output, configSha] = process.argv.slice(2)
fs.writeFileSync(output, `${JSON.stringify([{
  Config: `${configSha}.json`,
  RepoTags: ['postgres:fixture'],
  Layers: ['layer.tar'],
}])}\n`)
NODE
tar --sort=name --format=ustar --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -cf "$oci_fixture/postgres.oci.tar" -C "$oci_fixture" \
  manifest.json "$config_sha.json" layer.tar
cat >"$oci_fixture/postgres.cdx.json" <<'JSON'
{
  "bomFormat":"CycloneDX",
  "specVersion":"1.6",
  "version":1,
  "metadata":{"component":{"type":"container","name":"postgres-fixture","version":"1"}},
  "components":[{
    "type":"library",
    "name":"fixture",
    "version":"1.0",
    "purl":"pkg:deb/debian/fixture@1.0?arch=amd64&distro=debian-12",
    "licenses":[{"license":{"id":"MIT"}}]
  }]
}
JSON
: >"$fake_docker_log"
PATH="$fake_bin:$PATH" bash "$oci_source/deploy/scripts/generate-oci-license-closure.sh" \
    --archive "$oci_fixture/postgres.oci.tar" \
    --config-digest "sha256:$config_sha" \
    --kind postgres \
    --syft-raw-sbom "$oci_fixture/postgres.cdx.json" \
    --output "$oci_fixture/output-parent/postgres"
[[ ! -s "$fake_docker_log" ]] ||
  fail 'precomputed Syft OCI closure unexpectedly contacted Docker'
node - "$oci_fixture/output-parent/postgres/legal/manifest.json" "$config_sha" <<'NODE'
const fs = require('fs')
const [manifestPath, configSha] = process.argv.slice(2)
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
if (
  manifest.kind !== 'postgres' ||
  manifest.image?.configDigest !== `sha256:${configSha}` ||
  manifest.dpkgPackages?.length !== 1 ||
  manifest.dpkgPackages[0]?.identity !== 'fixture@1.0?arch=amd64'
) throw new Error('synthetic OCI legal closure differs')
NODE
expect_failure env PATH="$fake_bin:$PATH" \
  bash "$oci_source/deploy/scripts/generate-oci-license-closure.sh" \
    --archive "$oci_fixture/postgres.oci.tar" \
    --config-digest "sha256:$(printf '0%.0s' {1..64})" \
    --kind postgres \
    --syft-raw-sbom "$oci_fixture/postgres.cdx.json" \
    --output "$oci_fixture/output-parent/wrong-config"

# Finalization is closed, re-hashable, free of temporary payloads, and byte-identical across runs.
for tree in "$tmp/tree-a" "$tmp/tree-b"; do
  mkdir -p -- "$tree/licenses/example" "$tree/sbom"
  printf 'alpha\n' >"$tree/sbom/a.json"
  printf 'license\n' >"$tree/licenses/example/LICENSE"
  bash "$repo_root/deploy/scripts/finalize-compliance-tree.sh" --tree "$tree"
  (CDPATH='' cd -- "$tree" && sha256sum -c -- SHA256SUMS >/dev/null)
  if grep -Fq -- 'SHA256SUMS' "$tree/SHA256SUMS"; then fail 'checksum manifest self-references'; fi
  if [[ -n "$(find "$tree" -name '*.tmp*' -print -quit)" ]]; then fail 'temporary file leaked into compliance tree'; fi
done
diff -r --no-dereference -- "$tmp/tree-a" "$tmp/tree-b" >/dev/null || fail 'two compliance finalizations differ'
node "$repo_root/deploy/scripts/verify-compliance-tree.mjs" --tree "$tmp/tree-a"
printf 'tampered\n' >>"$tmp/tree-a/sbom/a.json"
expect_failure node "$repo_root/deploy/scripts/verify-compliance-tree.mjs" --tree "$tmp/tree-a"

# Two complete generator runs over the same offline fixture must be byte-identical.
generation_source="$tmp/generation-source"
generation_bin="$tmp/generation-bin"
mkdir -p -- \
  "$generation_source/frontend/public" \
  "$generation_source/frontend/src/assets" \
  "$generation_source/frontend/node_modules/.bin" \
  "$generation_source/frontend/node_modules/prod-fixture" \
  "$generation_source/admin-web/public" \
  "$generation_source/admin-web/src/assets" \
  "$generation_source/admin-web/node_modules/.bin" \
  "$generation_source/admin-web/node_modules/prod-fixture" \
  "$generation_source/backend-parent" \
  "$generation_source/python-site-packages" \
  "$generation_source/deploy/backup" \
  "$generation_source/deploy/compliance/license-texts" \
  "$generation_source/deploy/scripts" \
  "$generation_bin"
cp -- "$repo_root/deploy/compliance/toolchain.env" "$generation_source/deploy/compliance/toolchain.env"
for helper in \
  collect-node-licenses.mjs node-production-closure.mjs reconcile-node-sbom.mjs \
  collect-maven-licenses.mjs collect-python-licenses.py normalize-cyclonedx.mjs \
  extract-docker-image-rootfs.py collect-oci-image-licenses.mjs generate-oci-license-closure.sh \
  render-asset-provenance.mjs run-syft-sbom.sh finalize-compliance-tree.sh \
  verify-compliance-tree.mjs; do
  cp -- "$repo_root/deploy/scripts/$helper" "$generation_source/deploy/scripts/$helper"
done
for license_file in \
  vue-devtools-api-6.6.4-LICENSE.txt \
  nathan-rajlich-mit-LICENSE.txt \
  tencent-cos-java-sdk-v5-5.6.227-LICENSE.txt \
  bouncycastle-1.84-LICENSE.html \
  xmlpull-1.1.3.1-LICENSE.txt \
  crcmod-1.7-LICENSE.txt \
  tencentcloud-sdk-python-NOTICE.txt; do
  printf 'unused reviewed fixture text\n' >"$generation_source/deploy/compliance/license-texts/$license_file"
done
cat >"$generation_source/deploy/compliance/license-overrides.json" <<'JSON'
{"schemaVersion":1,"nodeComponents":["frontend","admin-web"],"packages":{},"mavenPackages":{}}
JSON
printf '%s\n' '{"schemaVersion":1,"packages":{}}' \
  >"$generation_source/deploy/compliance/python-legal-overrides.json"
cp -- "$repo_root/deploy/compliance/oci-legal-overrides.json" \
  "$generation_source/deploy/compliance/oci-legal-overrides.json"
printf '<svg xmlns="http://www.w3.org/2000/svg"/>\n' >"$generation_source/frontend/public/fixture.svg"
node - "$generation_source" <<'NODE'
const crypto = require('crypto')
const fs = require('fs')
const path = require('path')
const source = process.argv[2]
const relativePath = 'frontend/public/fixture.svg'
const bytes = fs.readFileSync(path.join(source, ...relativePath.split('/')))
fs.writeFileSync(
  path.join(source, 'deploy/compliance/assets.json'),
  `${JSON.stringify({
    schemaVersion: 2,
    licenseDefinitions: { 'LicenseRef-Fixture': 'Contract fixture.' },
    licenseNotices: {
      'LicenseRef-Fixture': {
        url: 'https://example.test/license',
        reviewedAt: '2026-07-19',
        summary: 'This reviewed fixture license permits the deterministic contract asset used by the compliance generator.',
      },
    },
    assets: [
      {
        path: relativePath,
        bytes: bytes.length,
        sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
        origin: 'Contract fixture',
        license: 'LicenseRef-Fixture',
        credit: 'Fixture author',
        sourceUrl: 'https://example.test/source',
      },
    ],
  })}\n`,
)
NODE
for project in frontend admin-web; do
  cat >"$generation_source/$project/package.json" <<'JSON'
{
  "name":"compliance-fixture",
  "version":"1.0.0",
  "scripts":{"sbom:prod":"cyclonedx-npm --package-lock-only --omit dev --spec-version 1.6 --output-reproducible --output-format JSON --validate"},
  "dependencies":{"prod-fixture":"1.0.0"},
  "devDependencies":{"@cyclonedx/cyclonedx-npm":"6.0.0","ajv":"8.20.0","ajv-formats":"3.0.1","ajv-formats-draft2019":"1.6.1"}
}
JSON
  cat >"$generation_source/$project/package-lock.json" <<'JSON'
{
  "name":"compliance-fixture",
  "version":"1.0.0",
  "lockfileVersion":3,
  "packages":{
    "":{"name":"compliance-fixture","version":"1.0.0","dependencies":{"prod-fixture":"1.0.0"},"devDependencies":{"@cyclonedx/cyclonedx-npm":"6.0.0","ajv":"8.20.0","ajv-formats":"3.0.1","ajv-formats-draft2019":"1.6.1"}},
    "node_modules/@cyclonedx/cyclonedx-npm":{"version":"6.0.0","dev":true,"integrity":"sha512-kpWjjV0j5y0mMHUB5dSx1hxweH8K2blSqkgdQ6eHgU7aClB4CcXGhbHtGY6WVHSo3A01Rt7WOLas/wQ1E+tBDg=="},
    "node_modules/ajv":{"version":"8.20.0","dev":true},
    "node_modules/ajv-formats":{"version":"3.0.1","dev":true},
    "node_modules/ajv-formats-draft2019":{"version":"1.6.1","dev":true},
    "node_modules/prod-fixture":{"version":"1.0.0","integrity":"sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}
  }
}
JSON
  cat >"$generation_source/$project/node_modules/prod-fixture/package.json" <<'JSON'
{"name":"prod-fixture","version":"1.0.0","license":"MIT"}
JSON
  printf 'MIT fixture license\n' >"$generation_source/$project/node_modules/prod-fixture/LICENSE"
  cat >"$generation_source/$project/node_modules/.bin/cyclonedx-npm" <<'BASH'
#!/usr/bin/env bash
printf '6.0.0\n'
BASH
  chmod 0700 -- "$generation_source/$project/node_modules/.bin/cyclonedx-npm"
done
python3 - "$generation_source/deploy/backup/requirements-cos-prune.txt" \
  "$generation_source/python-site-packages" <<'PY'
import pathlib
import sys

requirements = pathlib.Path(sys.argv[1])
site = pathlib.Path(sys.argv[2])
names = [
    'certifi', 'charset-normalizer', 'cos-python-sdk-v5', 'crcmod', 'idna', 'pycryptodome',
    'requests', 'six', 'tencentcloud-sdk-python-common', 'tencentcloud-sdk-python-sts',
    'urllib3', 'xmltodict',
]
lock = []
for index, name in enumerate(names, start=1):
    version = f'1.0.{index}'
    lock.append(f'{name}=={version} \\\n    --hash=sha256:{index:064x}\n')
    directory = site / f'{name.replace("-", "_")}-{version}.dist-info'
    licenses = directory / 'licenses'
    licenses.mkdir(parents=True)
    (directory / 'METADATA').write_text(
        f'Metadata-Version: 2.4\nName: {name}\nVersion: {version}\nLicense-Expression: MIT\nLicense-File: LICENSE.txt\n\n',
        encoding='utf-8',
    )
    (licenses / 'LICENSE.txt').write_text(f'MIT fixture license for {name}\n', encoding='utf-8')
    prefix = directory.relative_to(site).as_posix()
    (directory / 'RECORD').write_text(
        f'{prefix}/METADATA,,\n{prefix}/licenses/LICENSE.txt,,\n{prefix}/RECORD,,\n',
        encoding='utf-8',
    )
requirements.write_text(''.join(lock), encoding='utf-8')
PY
cat >"$generation_source/backend-parent/pom.xml" <<'XML'
<project>
  <properties><cyclonedx-maven.version>2.9.2</cyclonedx-maven.version></properties>
</project>
XML
cat >"$generation_source/backend-parent/mvnw" <<'BASH'
#!/usr/bin/env bash
set -euo pipefail
output=''
for argument in "$@"; do
  case "$argument" in
    -DoutputDirectory=*) output=${argument#*=} ;;
  esac
done
[[ -n "$output" ]]
fixture_jars=(
  org/springframework/boot/spring-boot-starter-web/1.0.0/spring-boot-starter-web-1.0.0.jar
  org/postgresql/postgresql/1.0.0/postgresql-1.0.0.jar
  com/qcloud/cos_api/1.0.0/cos_api-1.0.0.jar
  com/example/runtime-fixture/1.0.0/runtime-fixture-1.0.0.jar
)
for relative_jar in "${fixture_jars[@]}"; do
  mkdir -p -- "$output/$(dirname -- "$relative_jar")"
  printf 'deterministic fixture jar\n' >"$output/$relative_jar"
done
mkdir -p -- target
cat >target/portfolio-backend.cdx.json <<'JSON'
{
  "bomFormat":"CycloneDX",
  "specVersion":"1.6",
  "version":1,
  "metadata":{"component":{"type":"application","group":"xyz.yychainsaw","name":"portfolio-backend-parent","version":"1.0.0"}},
  "components":[
    {"type":"library","group":"xyz.yychainsaw","name":"portfolio-pojo","version":"1.0.0","purl":"pkg:maven/xyz.yychainsaw/portfolio-pojo@1.0.0"},
    {"type":"library","group":"xyz.yychainsaw","name":"portfolio-common","version":"1.0.0","purl":"pkg:maven/xyz.yychainsaw/portfolio-common@1.0.0"},
    {"type":"library","group":"xyz.yychainsaw","name":"portfolio-server","version":"1.0.0","purl":"pkg:maven/xyz.yychainsaw/portfolio-server@1.0.0"},
    {"type":"library","group":"org.springframework.boot","name":"spring-boot-starter-web","version":"1.0.0","purl":"pkg:maven/org.springframework.boot/spring-boot-starter-web@1.0.0","licenses":[{"license":{"id":"MIT"}}]},
    {"type":"library","group":"org.postgresql","name":"postgresql","version":"1.0.0","purl":"pkg:maven/org.postgresql/postgresql@1.0.0","licenses":[{"license":{"id":"MIT"}}]},
    {"type":"library","group":"com.qcloud","name":"cos_api","version":"1.0.0","purl":"pkg:maven/com.qcloud/cos_api@1.0.0","licenses":[{"license":{"id":"MIT"}}]},
    {"type":"library","group":"com.example","name":"runtime-fixture","version":"1.0.0","purl":"pkg:maven/com.example/runtime-fixture@1.0.0","licenses":[{"license":{"id":"MIT"}}]}
  ]
}
JSON
BASH
chmod 0700 -- "$generation_source/backend-parent/mvnw"
cat >"$generation_bin/npm" <<'BASH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == ci ]]; then
  : "${GENERATION_NODE_MODULES_TEMPLATE:?}"
  mkdir -p -- node_modules
  cp -a -- "$GENERATION_NODE_MODULES_TEMPLATE/." node_modules/
  exit 0
fi
if [[ "${GENERATION_SKIP_VALIDATION:-}" == 1 ]]; then
  printf '%s\n' 'WARN | skipped validating BOM: No JsonValidator available.' >&2
fi
printf '%s\n' '{"bomFormat":"CycloneDX","specVersion":"1.6","version":1,"metadata":{"component":{"type":"application","name":"compliance-fixture","version":"1.0.0"}},"components":[{"type":"library","name":"prod-fixture","version":"1.0.0","purl":"pkg:npm/prod-fixture@1.0.0","licenses":[{"license":{"id":"MIT"}}]}]}'
BASH
cat >"$generation_bin/jq" <<'BASH'
#!/usr/bin/env bash
exit 0
BASH
cat >"$generation_bin/unzip" <<'BASH'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  -Z1) printf 'META-INF/LICENSE\n' ;;
  -p) printf 'MIT fixture license\n' ;;
  *) exit 2 ;;
esac
BASH
chmod 0700 -- "$generation_bin/npm" "$generation_bin/jq" "$generation_bin/unzip"
for output in "$tmp/generated-a" "$tmp/generated-b"; do
  GENERATION_NODE_MODULES_TEMPLATE="$generation_source/frontend/node_modules" \
    PATH="$generation_bin:$PATH" bash "$repo_root/deploy/scripts/generate-compliance-artifacts.sh" \
    --source "$generation_source" --output "$output" \
    --python-site-packages "$generation_source/python-site-packages"
  node "$repo_root/deploy/scripts/verify-compliance-tree.mjs" --tree "$output"
done
diff -r --no-dereference -- "$tmp/generated-a" "$tmp/generated-b" >/dev/null ||
  fail 'two complete compliance generator runs differ'
expect_failure env GENERATION_SKIP_VALIDATION=1 \
  GENERATION_NODE_MODULES_TEMPLATE="$generation_source/frontend/node_modules" \
  PATH="$generation_bin:$PATH" bash "$repo_root/deploy/scripts/generate-compliance-artifacts.sh" \
    --source "$generation_source" --output "$tmp/generated-skipped-validation" \
    --python-site-packages "$generation_source/python-site-packages"
[[ ! -e "$tmp/generated-skipped-validation" ]] ||
  fail 'generator published a compliance tree after CycloneDX validation was skipped'

# TERM must clean staging and must never publish the atomic output directory.
signal_source="$tmp/signal-source"
required_sources=(
  frontend/package.json frontend/package-lock.json
  admin-web/package.json admin-web/package-lock.json
  backend-parent/pom.xml backend-parent/mvnw
  deploy/compliance/toolchain.env deploy/compliance/assets.json
  deploy/compliance/license-overrides.json
  deploy/compliance/license-texts/vue-devtools-api-6.6.4-LICENSE.txt
  deploy/compliance/license-texts/nathan-rajlich-mit-LICENSE.txt
  deploy/compliance/license-texts/tencent-cos-java-sdk-v5-5.6.227-LICENSE.txt
  deploy/compliance/license-texts/bouncycastle-1.84-LICENSE.html
  deploy/compliance/license-texts/xmlpull-1.1.3.1-LICENSE.txt
  deploy/backup/requirements-cos-prune.txt
  deploy/compliance/python-legal-overrides.json
  deploy/compliance/oci-legal-overrides.json
  deploy/compliance/license-texts/crcmod-1.7-LICENSE.txt
  deploy/compliance/license-texts/tencentcloud-sdk-python-NOTICE.txt
  deploy/scripts/collect-node-licenses.mjs
  deploy/scripts/node-production-closure.mjs
  deploy/scripts/reconcile-node-sbom.mjs
  deploy/scripts/collect-maven-licenses.mjs
  deploy/scripts/collect-python-licenses.py
  deploy/scripts/extract-docker-image-rootfs.py
  deploy/scripts/collect-oci-image-licenses.mjs
  deploy/scripts/generate-oci-license-closure.sh
  deploy/scripts/normalize-cyclonedx.mjs
  deploy/scripts/render-asset-provenance.mjs
  deploy/scripts/run-syft-sbom.sh
  deploy/scripts/finalize-compliance-tree.sh
  deploy/scripts/verify-compliance-tree.mjs
)
for source_path in "${required_sources[@]}"; do
  mkdir -p -- "$signal_source/$(dirname -- "$source_path")"
  cp -- "$repo_root/$source_path" "$signal_source/$source_path"
done
for project in frontend admin-web; do
  mkdir -p -- "$signal_source/$project/node_modules/.bin"
  cat >"$signal_source/$project/node_modules/.bin/cyclonedx-npm" <<'BASH'
#!/usr/bin/env bash
printf '6.0.0\n'
BASH
  chmod 0700 -- "$signal_source/$project/node_modules/.bin/cyclonedx-npm"
done
signal_bin="$tmp/signal-bin"
signal_marker="$tmp/npm-started"
signal_log="$tmp/signal.log"
signal_output="$tmp/published-compliance"
mkdir -p -- "$signal_bin"
cat >"$signal_bin/npm" <<'BASH'
#!/usr/bin/env bash
set -euo pipefail
: >"$SIGNAL_MARKER"
exec sleep 300
BASH
for command_name in jq unzip; do
  cp -- "$signal_bin/npm" "$signal_bin/$command_name"
done
chmod 0700 -- "$signal_bin/npm" "$signal_bin/jq" "$signal_bin/unzip"
setsid env "PATH=$signal_bin:$PATH" "SIGNAL_MARKER=$signal_marker" \
  bash "$repo_root/deploy/scripts/generate-compliance-artifacts.sh" \
  --source "$signal_source" --output "$signal_output" >"$signal_log" 2>&1 &
signal_pid=$!
for _ in $(seq 1 100); do
  [[ -e "$signal_marker" ]] && break
  kill -0 "$signal_pid" 2>/dev/null || break
  sleep 0.05
done
[[ -e "$signal_marker" ]] || { sed -n '1,120p' "$signal_log" >&2; fail 'generator did not reach interrupt fixture'; }
kill -TERM -- "-$signal_pid"
set +e
wait "$signal_pid"
signal_status=$?
set -e
[[ "$signal_status" == 143 ]] || fail "generator TERM status was $signal_status, expected 143"
[[ ! -e "$signal_output" && ! -L "$signal_output" ]] || fail 'generator published output after TERM'
if [[ -n "$(find "$tmp" -maxdepth 1 -name '.compliance.*' -print -quit)" ]]; then
  fail 'generator left staging content after TERM'
fi

printf 'compliance-contract: PASS\n'
