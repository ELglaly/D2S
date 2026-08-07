# PostToolUse hook: advisory checks for SchoolBridge's known gotchas (docs/COMMON_MISTAKES.md).
# Reads the Claude Code hook JSON payload from stdin, inspects the file that was just
# written/edited, and exits 2 (non-blocking for PostToolUse, but surfaces stderr to Claude)
# if a known-risky pattern is present. Exits 0 silently otherwise. Never edits anything.

$ErrorActionPreference = "SilentlyContinue"

$raw = [Console]::In.ReadToEnd()
if (-not $raw) { exit 0 }

try {
  $payload = $raw | ConvertFrom-Json
} catch {
  exit 0
}

$filePath = $payload.tool_input.file_path
if (-not $filePath) { exit 0 }
if (-not (Test-Path $filePath)) { exit 0 }
if ($filePath -notmatch '\.java$') { exit 0 }

$content = Get-Content -Raw -LiteralPath $filePath
if (-not $content) { exit 0 }

$warnings = @()

# 1. Tenant repository missing the findById @Query override (docs/COMMON_MISTAKES.md #1)
if ($filePath -match 'Repository\.java$') {
  $entityMatch = [regex]::Match($content, 'extends\s+JpaRepository<\s*(\w+)\s*,')
  if ($entityMatch.Success) {
    $entityName = $entityMatch.Groups[1].Value
    $entityFile = Get-ChildItem -Path (Join-Path (Split-Path $filePath -Parent) '..') `
      -Recurse -Filter "$entityName.java" -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if (-not $entityFile) {
      $entityFile = Get-ChildItem -Path "src/main/java" -Recurse -Filter "$entityName.java" `
        -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if ($entityFile) {
      $entityContent = Get-Content -Raw -LiteralPath $entityFile.FullName
      if ($entityContent -match 'extends\s+TenantEntity') {
        $hasOverride = $content -match '@Query[^;]*?\bfindById\b' -or
                        ($content -match 'findById' -and $content -match '@Query')
        if (-not $hasOverride) {
          $warnings += "TENANT ISOLATION: $entityName extends TenantEntity but $([IO.Path]::GetFileName($filePath)) has no findById @Query override. Hibernate @Filter does NOT apply to EntityManager.find() -- see docs/adr/ADR-002-tenant-isolation.md and docs/COMMON_MISTAKES.md #1."
        }
      }
    }
  }
}

# 2. Map.of(...) near outbox/audit record() calls (docs/COMMON_MISTAKES.md #3)
if (($content -match 'OutboxEventRecorder' -or $content -match 'AuditService' -or $content -match '\.record\(') `
    -and $content -match 'Map\.of\(') {
  $warnings += "OUTBOX/AUDIT: Map.of( found in a file that also calls an outbox/audit record(...). Map.of throws NPE on any null field -- use HashMap instead. See docs/COMMON_MISTAKES.md #3."
}

# 3. AIP colon-verb path in a mapping annotation (docs/adr/ADR-006)
if ($content -match '@(Get|Post|Put|Delete|Patch)Mapping\([^)]*:[a-zA-Z]') {
  $warnings += "ROUTING: colon-verb path (e.g. /resource:action) found in a *Mapping annotation. Real HTTP clients percent-encode ':' and the request 404s. Use slash-style (/resource/action) -- see docs/adr/ADR-006-slash-style-action-paths.md."
}

# 4. RestClient built without an explicit request factory (docs/COMMON_MISTAKES.md #4)
if ($content -match 'RestClient\.builder\(\)' -and
    $content -notmatch 'SimpleClientHttpRequestFactory' -and
    $content -notmatch 'HttpComponentsClientHttpRequestFactory') {
  $warnings += "RESTCLIENT: RestClient.builder() with no explicit requestFactory. Default JDK HttpClient factory aborts mid-write against Jetty-12 test servers on Windows JDK 23 -- pass SimpleClientHttpRequestFactory (or HttpComponentsClientHttpRequestFactory). See docs/COMMON_MISTAKES.md #4."
}

# 5. RestClient baseUrl with a path prefix + a leading-slash .uri(...) call (docs/COMMON_MISTAKES.md #5)
if ($content -match '\.baseUrl\([^)]*/[a-zA-Z0-9_-]+["\x27]\s*\)' -and $content -match '\.uri\(\s*["\x27]/') {
  $warnings += "RESTCLIENT: baseUrl(...) with a path segment plus a .uri('/...') call -- DefaultUriBuilderFactory REPLACES the base path, it doesn't append. Double-check the base path survives. See docs/COMMON_MISTAKES.md #5."
}

# 6. UsernamePasswordAuthenticationToken 3-arg ctor followed by setAuthenticated(true) (docs/COMMON_MISTAKES.md #2)
if ($content -match 'new\s+UsernamePasswordAuthenticationToken\(' -and $content -match '\.setAuthenticated\(true\)') {
  $warnings += "AUTH: UsernamePasswordAuthenticationToken(...) 3-arg constructor already marks the token authenticated -- calling setAuthenticated(true) afterward throws. See docs/COMMON_MISTAKES.md #2."
}

# 7. ResponseBodyAdvice missing one of the two required supports() guards (docs/COMMON_MISTAKES.md #7)
if ($filePath -match 'ResponseBodyAdvice\.java$' -and $content -match 'boolean supports\(') {
  $hasConverterGuard = $content -match 'MappingJackson2HttpMessageConverter'
  $hasPackageGuard = $content -match 'com\.schoolbridge\.api'
  if (-not ($hasConverterGuard -and $hasPackageGuard)) {
    $warnings += "RESPONSE ADVICE: supports() should check BOTH the Jackson converter type AND the com.schoolbridge.api package, or it wraps String responses (webhook challenges) and Actuator endpoints too. See docs/COMMON_MISTAKES.md #7."
  }
}

if ($warnings.Count -eq 0) { exit 0 }

foreach ($w in $warnings) {
  [Console]::Error.WriteLine("[gotcha-check] $w")
}
exit 2
