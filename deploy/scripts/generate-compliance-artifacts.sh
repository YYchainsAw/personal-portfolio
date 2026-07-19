#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
export TZ=UTC
umask 077

die() {
  printf 'generate-compliance-artifacts: %s\n' "$*" >&2
  exit 1
}

note() {
  printf 'generate-compliance-artifacts: %s\n' "$*" >&2
}

script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
default_source="$(CDPATH='' cd -- "$script_dir/../.." && pwd -P)"
source_dir="$default_source"
output=''
source_date_epoch=946684800
api_oci=''
postgres_oci=''
api_config_digest=''
postgres_config_digest=''
python_site_packages_input=''
api_syft_raw=''
postgres_syft_raw=''

while (($# > 0)); do
  case "$1" in
    --source) (($# >= 2)) || die 'missing --source value'; source_dir=$2; shift 2 ;;
    --output) (($# >= 2)) || die 'missing --output value'; output=$2; shift 2 ;;
    --source-date-epoch) (($# >= 2)) || die 'missing --source-date-epoch value'; source_date_epoch=$2; shift 2 ;;
    --api-oci) (($# >= 2)) || die 'missing --api-oci value'; api_oci=$2; shift 2 ;;
    --postgres-oci) (($# >= 2)) || die 'missing --postgres-oci value'; postgres_oci=$2; shift 2 ;;
    --api-config-digest) (($# >= 2)) || die 'missing --api-config-digest value'; api_config_digest=$2; shift 2 ;;
    --postgres-config-digest) (($# >= 2)) || die 'missing --postgres-config-digest value'; postgres_config_digest=$2; shift 2 ;;
    --python-site-packages) (($# >= 2)) || die 'missing --python-site-packages value'; python_site_packages_input=$2; shift 2 ;;
    --api-syft-raw) (($# >= 2)) || die 'missing --api-syft-raw value'; api_syft_raw=$2; shift 2 ;;
    --postgres-syft-raw) (($# >= 2)) || die 'missing --postgres-syft-raw value'; postgres_syft_raw=$2; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ -n "$output" ]] || die 'usage: generate-compliance-artifacts.sh --output DIR [--source DIR] [--python-site-packages DIR] [--api-oci ARCHIVE --api-config-digest SHA256 --postgres-oci ARCHIVE --postgres-config-digest SHA256]'
[[ "$source_date_epoch" =~ ^[0-9]{1,12}$ ]] || die 'source date epoch must be a non-negative integer'
if [[ -n "$api_oci$postgres_oci$api_config_digest$postgres_config_digest" ]]; then
  [[ -n "$api_oci" && -n "$postgres_oci" && -n "$api_config_digest" && -n "$postgres_config_digest" ]] ||
    die 'API/PostgreSQL archives and exact config digests must be provided together'
  [[ "$api_config_digest" =~ ^sha256:[0-9a-f]{64}$ &&
     "$postgres_config_digest" =~ ^sha256:[0-9a-f]{64}$ ]] ||
    die 'OCI config digests must be exact lowercase SHA-256 identities'
fi
if [[ -n "$api_syft_raw$postgres_syft_raw" ]]; then
  [[ -n "$api_oci" && -n "$api_syft_raw" && -n "$postgres_syft_raw" ]] ||
    die 'API/PostgreSQL precomputed Syft SBOMs require both OCI archives and both SBOM inputs'
fi

for command_name in basename cat chmod cp dirname find grep jq mkdir mktemp mv node npm python3 realpath rm sha256sum sort stat unzip; do
  command -v "$command_name" >/dev/null 2>&1 || die "missing command: $command_name"
done

[[ -d "$source_dir" && ! -L "$source_dir" ]] || die 'source must be a non-symlink directory'
source_dir="$(realpath -e -- "$source_dir")"
for required_dir in frontend admin-web backend-parent deploy; do
  candidate="$source_dir/$required_dir"
  [[ -d "$candidate" && ! -L "$candidate" ]] || die "required source directory must be a non-symlink directory: $required_dir"
  candidate_real="$(realpath -e -- "$candidate")"
  [[ "$candidate_real" == "$source_dir"/* ]] || die "required source directory escapes source: $required_dir"
done
if [[ -n "$python_site_packages_input" ]]; then
  [[ -d "$python_site_packages_input" && ! -L "$python_site_packages_input" ]] ||
    die 'prebuilt Python site-packages must be a non-symlink directory'
  python_site_packages_input="$(realpath -e -- "$python_site_packages_input")"
  [[ "$python_site_packages_input" == "$source_dir"/* ]] ||
    die 'prebuilt Python site-packages must remain inside the reviewed source tree'
fi
for syft_input in "$api_syft_raw" "$postgres_syft_raw"; do
  [[ -z "$syft_input" ]] && continue
  [[ -f "$syft_input" && ! -L "$syft_input" ]] ||
    die 'precomputed Syft input must be a regular non-symlink file'
done
for required in \
  frontend/package.json frontend/package-lock.json \
  admin-web/package.json admin-web/package-lock.json \
  backend-parent/pom.xml backend-parent/mvnw \
  deploy/compliance/toolchain.env deploy/compliance/assets.json \
  deploy/compliance/license-overrides.json \
  deploy/compliance/license-texts/vue-devtools-api-6.6.4-LICENSE.txt \
  deploy/compliance/license-texts/nathan-rajlich-mit-LICENSE.txt \
  deploy/compliance/license-texts/tencent-cos-java-sdk-v5-5.6.227-LICENSE.txt \
  deploy/compliance/license-texts/bouncycastle-1.84-LICENSE.html \
  deploy/compliance/license-texts/xmlpull-1.1.3.1-LICENSE.txt \
  deploy/backup/requirements-cos-prune.txt \
  deploy/compliance/python-legal-overrides.json \
  deploy/compliance/license-texts/crcmod-1.7-LICENSE.txt \
  deploy/compliance/license-texts/tencentcloud-sdk-python-NOTICE.txt \
  deploy/compliance/oci-legal-overrides.json \
  deploy/scripts/collect-node-licenses.mjs \
  deploy/scripts/node-production-closure.mjs \
  deploy/scripts/reconcile-node-sbom.mjs \
  deploy/scripts/collect-maven-licenses.mjs \
  deploy/scripts/collect-python-licenses.py \
  deploy/scripts/extract-docker-image-rootfs.py \
  deploy/scripts/collect-oci-image-licenses.mjs \
  deploy/scripts/generate-oci-license-closure.sh \
  deploy/scripts/normalize-cyclonedx.mjs \
  deploy/scripts/render-asset-provenance.mjs \
  deploy/scripts/run-syft-sbom.sh \
  deploy/scripts/finalize-compliance-tree.sh \
  deploy/scripts/verify-compliance-tree.mjs; do
  [[ -f "$source_dir/$required" && ! -L "$source_dir/$required" ]] || die "missing reviewed source file: $required"
  required_real="$(realpath -e -- "$source_dir/$required")"
  [[ "$required_real" == "$source_dir"/* ]] || die "reviewed source file escapes source: $required"
done

output_parent="$(realpath -e -- "$(dirname -- "$output")")"
output_name="$(basename -- "$output")"
[[ "$output_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ && "$output_name" != '.' && "$output_name" != '..' ]] ||
  die 'output basename is unsafe'
output="$output_parent/$output_name"
[[ ! -e "$output" && ! -L "$output" ]] || die 'output already exists'
staging="$(mktemp -d -- "$output_parent/.compliance.XXXXXXXXXX")"
cleanup() {
  [[ -n "${staging:-}" && -d "$staging" ]] && rm -rf --one-file-system -- "$staging"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

toolchain="$source_dir/deploy/compliance/toolchain.env"
CYCLONEDX_SPEC_VERSION=''
CYCLONEDX_NPM_VERSION=''
CYCLONEDX_NPM_INTEGRITY=''
CYCLONEDX_VALIDATOR_AJV_VERSION=''
CYCLONEDX_VALIDATOR_FORMATS_VERSION=''
CYCLONEDX_VALIDATOR_DRAFT2019_VERSION=''
CYCLONEDX_MAVEN_PLUGIN_VERSION=''
MAVEN_DEPENDENCY_PLUGIN_VERSION=''
SYFT_PLATFORM=''
SYFT_IMAGE=''
declare -A seen=()
while IFS='=' read -r key value || [[ -n "$key$value" ]]; do
  [[ -z "$key" || "$key" == \#* ]] && continue
  [[ -z "${seen[$key]+x}" ]] || die "duplicate toolchain key: $key"
  seen[$key]=1
  case "$key" in
    CYCLONEDX_SPEC_VERSION) CYCLONEDX_SPEC_VERSION=$value ;;
    CYCLONEDX_NPM_VERSION) CYCLONEDX_NPM_VERSION=$value ;;
    CYCLONEDX_NPM_INTEGRITY) CYCLONEDX_NPM_INTEGRITY=$value ;;
    CYCLONEDX_VALIDATOR_AJV_VERSION) CYCLONEDX_VALIDATOR_AJV_VERSION=$value ;;
    CYCLONEDX_VALIDATOR_FORMATS_VERSION) CYCLONEDX_VALIDATOR_FORMATS_VERSION=$value ;;
    CYCLONEDX_VALIDATOR_DRAFT2019_VERSION) CYCLONEDX_VALIDATOR_DRAFT2019_VERSION=$value ;;
    CYCLONEDX_MAVEN_PLUGIN_VERSION) CYCLONEDX_MAVEN_PLUGIN_VERSION=$value ;;
    MAVEN_DEPENDENCY_PLUGIN_VERSION) MAVEN_DEPENDENCY_PLUGIN_VERSION=$value ;;
    SYFT_PLATFORM) SYFT_PLATFORM=$value ;;
    SYFT_IMAGE) SYFT_IMAGE=$value ;;
    *) die "unexpected toolchain key: $key" ;;
  esac
done <"$toolchain"

[[ "$CYCLONEDX_SPEC_VERSION" == '1.6' ]] || die 'unexpected CycloneDX specification version'
[[ "$CYCLONEDX_NPM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die 'invalid CycloneDX npm version'
[[ "$CYCLONEDX_NPM_INTEGRITY" =~ ^sha512-[A-Za-z0-9+/]+={0,2}$ ]] || die 'invalid CycloneDX npm integrity'
[[ "$CYCLONEDX_VALIDATOR_AJV_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ &&
   "$CYCLONEDX_VALIDATOR_FORMATS_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ &&
   "$CYCLONEDX_VALIDATOR_DRAFT2019_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
  die 'invalid CycloneDX JSON validator versions'
[[ "$CYCLONEDX_MAVEN_PLUGIN_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die 'invalid CycloneDX Maven version'
[[ "$MAVEN_DEPENDENCY_PLUGIN_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die 'invalid Maven dependency plugin version'
[[ "$SYFT_PLATFORM" == 'linux/amd64' ]] || die 'unexpected Syft target platform'
[[ "$SYFT_IMAGE" =~ ^docker\.io/anchore/syft:v[0-9]+\.[0-9]+\.[0-9]+@sha256:[0-9a-f]{64}$ && "$SYFT_IMAGE" != *latest* ]] ||
  die 'Syft image is not immutable'

node - "$source_dir" "$CYCLONEDX_NPM_VERSION" "$CYCLONEDX_NPM_INTEGRITY" \
  "$CYCLONEDX_VALIDATOR_AJV_VERSION" "$CYCLONEDX_VALIDATOR_FORMATS_VERSION" \
  "$CYCLONEDX_VALIDATOR_DRAFT2019_VERSION" <<'NODE'
const fs = require('fs')
const path = require('path')
const [source, expectedVersion, expectedIntegrity, ajvVersion, formatsVersion, draft2019Version] = process.argv.slice(2)
for (const project of ['frontend', 'admin-web']) {
  const pkg = JSON.parse(fs.readFileSync(path.join(source, project, 'package.json'), 'utf8'))
  const lock = JSON.parse(fs.readFileSync(path.join(source, project, 'package-lock.json'), 'utf8'))
  const pinned = pkg.devDependencies?.['@cyclonedx/cyclonedx-npm']
  const rootPinned = lock.packages?.['']?.devDependencies?.['@cyclonedx/cyclonedx-npm']
  const tool = lock.packages?.['node_modules/@cyclonedx/cyclonedx-npm']
  if (pinned !== expectedVersion || rootPinned !== expectedVersion || tool?.version !== expectedVersion) {
    throw new Error(`${project}: CycloneDX npm tool is not exactly pinned`)
  }
  if (tool.integrity !== expectedIntegrity || tool.dev !== true) {
    throw new Error(`${project}: CycloneDX npm integrity/dev boundary mismatch`)
  }
  const validators = {
    ajv: ajvVersion,
    'ajv-formats': formatsVersion,
    'ajv-formats-draft2019': draft2019Version,
  }
  for (const [name, version] of Object.entries(validators)) {
    if (pkg.devDependencies?.[name] !== version || lock.packages?.['']?.devDependencies?.[name] !== version) {
      throw new Error(`${project}: CycloneDX validator ${name} is not directly and exactly pinned`)
    }
    const installed = lock.packages?.[`node_modules/${name}`]
    if (installed?.version !== version || installed.dev !== true) {
      throw new Error(`${project}: CycloneDX validator ${name} lock identity/dev boundary mismatch`)
    }
  }
  const command = pkg.scripts?.['sbom:prod'] ?? ''
  for (const required of ['--package-lock-only', '--omit dev', '--spec-version 1.6', '--output-reproducible', '--validate']) {
    if (!command.includes(required)) throw new Error(`${project}: sbom:prod is missing ${required}`)
  }
}
NODE
grep -Fq "<cyclonedx-maven.version>$CYCLONEDX_MAVEN_PLUGIN_VERSION</cyclonedx-maven.version>" \
  "$source_dir/backend-parent/pom.xml" || die 'POM CycloneDX Maven pin does not match toolchain'

mkdir -p -- "$staging/sbom" "$staging/licenses" "$staging/work"
for project in frontend admin-web; do
  node_project="$staging/work/node-$project"
  mkdir -p -- "$node_project"
  cp -- "$source_dir/$project/package.json" "$source_dir/$project/package-lock.json" "$node_project/"
  note "installing the exact $project Node dependency closure"
  (
    CDPATH='' cd -- "$node_project"
    npm_config_cache="$staging/work/npm-cache" \
      npm ci --ignore-scripts --no-audit --no-fund
  )
  local_tool="$node_project/node_modules/.bin/cyclonedx-npm"
  [[ -x "$local_tool" || -x "$local_tool.cmd" ]] || die "$project clean install has no CycloneDX npm tool"
  actual_version="$(CDPATH='' cd -- "$node_project" && ./node_modules/.bin/cyclonedx-npm --version)"
  [[ "$actual_version" == "$CYCLONEDX_NPM_VERSION" ]] || die "$project local CycloneDX npm version mismatch"
  note "generating $project production SBOM"
  raw="$staging/work/$project.raw.cdx.json"
  sbom_log="$staging/work/$project.sbom.stderr"
  if ! (CDPATH='' cd -- "$node_project" && npm run --silent sbom:prod) >"$raw" 2>"$sbom_log"; then
    cat -- "$sbom_log" >&2
    die "$project CycloneDX generation or validation failed"
  fi
  cat -- "$sbom_log" >&2
  if grep -Fq -- 'skipped validating BOM' "$sbom_log" || grep -Fq -- 'No JsonValidator available' "$sbom_log"; then
    die "$project CycloneDX JSON validation was skipped"
  fi
  [[ -s "$raw" ]] || die "$project CycloneDX output is empty"
  node "$source_dir/deploy/scripts/collect-node-licenses.mjs" \
    --project "$node_project" \
    --component "$project" \
    --output "$staging/licenses/$project" \
    --overrides "$source_dir/deploy/compliance/license-overrides.json"
  node "$source_dir/deploy/scripts/reconcile-node-sbom.mjs" \
    --lock "$source_dir/$project/package-lock.json" \
    --raw-sbom "$raw" \
    --license-manifest "$staging/licenses/$project/manifest.json" \
    --output "$staging/sbom/$project.cdx.json"
done

note 'building aggregate backend SBOM and runtime dependency closure'
maven_repository="$staging/work/maven-runtime"
mkdir -p -- "$maven_repository"
(
CDPATH='' cd -- "$source_dir/backend-parent"
  ./mvnw -B -DskipTests \
    "-Dproject.build.outputTimestamp=$source_date_epoch" \
    package \
    "org.apache.maven.plugins:maven-dependency-plugin:$MAVEN_DEPENDENCY_PLUGIN_VERSION:copy-dependencies" \
    -DincludeScope=runtime \
    -DexcludeGroupIds=xyz.yychainsaw \
    "-DoutputDirectory=$maven_repository" \
    -Dmdep.useRepositoryLayout=true
)
backend_raw="$source_dir/backend-parent/target/portfolio-backend.cdx.json"
[[ -s "$backend_raw" && ! -L "$backend_raw" ]] || die 'Maven did not produce the aggregate backend SBOM'
node "$source_dir/deploy/scripts/normalize-cyclonedx.mjs" \
  --input "$backend_raw" --output "$staging/sbom/backend.cdx.json" --require-licenses

jq -e '
  [.. | objects | .purl? // empty] as $purls |
  def has($needle): any($purls[]; contains($needle));
  has("pkg:maven/xyz.yychainsaw/portfolio-pojo@") and
  has("pkg:maven/xyz.yychainsaw/portfolio-common@") and
  has("pkg:maven/xyz.yychainsaw/portfolio-server@") and
  has("pkg:maven/org.springframework.boot/spring-boot-starter-web@") and
  has("pkg:maven/org.postgresql/postgresql@") and
  has("pkg:maven/com.qcloud/cos_api@") and
  ([ $purls[] | select(test("pkg:maven/(org\\.testcontainers|org\\.junit|junit)/")) ] | length == 0)
' "$staging/sbom/backend.cdx.json" >/dev/null || die 'backend SBOM scope/module contract failed'

node "$source_dir/deploy/scripts/collect-maven-licenses.mjs" \
  --repository "$maven_repository" \
  --sbom "$staging/sbom/backend.cdx.json" \
  --output "$staging/licenses/backend" \
  --overrides "$source_dir/deploy/compliance/license-overrides.json"

note 'building hash-locked Ubuntu 22.04 CPython 3.10 COS runtime closure'
if [[ -n "$python_site_packages_input" ]]; then
  python_site_packages=$python_site_packages_input
else
  [[ "$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')" == '3.10' ]] ||
    die 'COS runtime closure requires Ubuntu CPython 3.10'
  python_venv="$staging/work/cos-prune-venv"
  python3 -m venv "$python_venv"
  PIP_DISABLE_PIP_VERSION_CHECK=1 PIP_NO_INPUT=1 \
    "$python_venv/bin/python3" -m pip install \
      --require-hashes --no-deps --no-build-isolation --only-binary=:all: \
      --no-binary=crcmod \
      -r "$source_dir/deploy/backup/requirements-cos-prune.txt"
  PIP_DISABLE_PIP_VERSION_CHECK=1 "$python_venv/bin/python3" -m pip check
  PIP_DISABLE_PIP_VERSION_CHECK=1 "$python_venv/bin/python3" -m pip uninstall \
    --yes pip setuptools >/dev/null
  python_site_packages="$("$python_venv/bin/python3" -c \
    'import sysconfig; print(sysconfig.get_paths()["purelib"])')"
fi
python3 "$source_dir/deploy/scripts/collect-python-licenses.py" \
  --requirements "$source_dir/deploy/backup/requirements-cos-prune.txt" \
  --overrides "$source_dir/deploy/compliance/python-legal-overrides.json" \
  --site-packages "$python_site_packages" \
  --output "$staging/licenses/cos-prune-runtime" \
  --expected-count 12

node "$source_dir/deploy/scripts/render-asset-provenance.mjs" \
  --source "$source_dir" \
  --manifest "$source_dir/deploy/compliance/assets.json" \
  --output "$staging/ASSET_PROVENANCE.md"
cp -- "$source_dir/deploy/compliance/assets.json" "$staging/asset-provenance.json"
cp -- "$source_dir/deploy/compliance/license-overrides.json" "$staging/license-overrides.json"
cp -- "$source_dir/deploy/compliance/python-legal-overrides.json" "$staging/python-legal-overrides.json"
cp -- "$source_dir/deploy/compliance/oci-legal-overrides.json" "$staging/oci-legal-overrides.json"
cp -- "$toolchain" "$staging/toolchain.env"

if [[ -n "$api_oci" ]]; then
  if [[ -z "$api_syft_raw" ]]; then
    command -v docker >/dev/null 2>&1 ||
      die 'Docker is required for OCI legal closure generation without precomputed Syft SBOMs'
  fi
  mkdir -p -- "$staging/oci"
  for name in api-image postgres-image; do
    if [[ "$name" == api-image ]]; then
      archive=$api_oci
      config_digest=$api_config_digest
      kind=api
      syft_raw=$api_syft_raw
    else
      archive=$postgres_oci
      config_digest=$postgres_config_digest
      kind=postgres
      syft_raw=$postgres_syft_raw
    fi
    [[ -f "$archive" && ! -L "$archive" ]] || die "$name OCI input is not a regular file"
    archive="$(realpath -e -- "$archive")"
    scan_archive=$archive
    if [[ "$archive" == *.zst ]]; then
      command -v zstd >/dev/null 2>&1 || die 'zstd is required for compressed OCI archives'
      scan_archive="$staging/work/$name.oci.tar"
      zstd -q -dc -- "$archive" >"$scan_archive"
    fi
    closure="$staging/work/$name-closure"
    closure_args=(
      --archive "$scan_archive"
      --config-digest "$config_digest"
      --kind "$kind"
      --output "$closure"
    )
    if [[ "$kind" == api ]]; then
      closure_args+=(--backend-licenses "$staging/licenses/backend")
    fi
    if [[ -n "$syft_raw" ]]; then
      closure_args+=(--syft-raw-sbom "$syft_raw")
    fi
    bash "$source_dir/deploy/scripts/generate-oci-license-closure.sh" "${closure_args[@]}"
    mkdir -p -- "$staging/licenses/$name"
    cp -a -- "$closure/legal/." "$staging/licenses/$name/"
    cp -- "$closure/sbom.cdx.json" "$staging/sbom/$name.cdx.json"
    cp -- "$closure/image-metadata.json" "$staging/oci/$name-metadata.json"
  done
fi

{
  printf 'THIRD-PARTY NOTICES - Yi Jiaxuan Portfolio\n\n'
  for component in frontend admin-web backend cos-prune-runtime; do
    cat -- "$staging/licenses/$component/THIRD_PARTY_NOTICES.txt"
    printf '\n'
  done
  if [[ -n "$api_oci" ]]; then
    for component in api-image postgres-image; do
      cat -- "$staging/licenses/$component/THIRD_PARTY_NOTICES.txt"
      printf '\n'
    done
  fi
} >"$staging/THIRD_PARTY_NOTICES.txt"

rm -rf --one-file-system -- "$staging/work"
"$source_dir/deploy/scripts/finalize-compliance-tree.sh" --tree "$staging"
node "$source_dir/deploy/scripts/verify-compliance-tree.mjs" --tree "$staging"

mv -T -- "$staging" "$output"
staging=''
trap - EXIT HUP INT TERM
note "wrote compliance tree: $output"
