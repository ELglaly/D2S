param(
  [string]$OutDir = "postman"
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$sampleUuid = "11111111-1111-1111-1111-111111111111"
$missingUuid = "00000000-0000-0000-0000-000000000404"

function JsonBody($obj) {
  return @{
    mode = "raw"
    raw = ($obj | ConvertTo-Json -Depth 20)
    options = @{ raw = @{ language = "json" } }
  }
}

function TextBody([string]$text) {
  return @{
    mode = "raw"
    raw = $text
    options = @{ raw = @{ language = "text" } }
  }
}

function FormDataBody($items) {
  return @{
    mode = "formdata"
    formdata = $items
  }
}

function UrlObject([string]$path, [hashtable]$query = @{}) {
  $pathOnly = $path
  $inlineQuery = @{}
  if ($path.Contains("?")) {
    $parts = $path.Split("?", 2)
    $pathOnly = $parts[0]
    foreach ($pair in $parts[1].Split("&")) {
      if (-not [string]::IsNullOrWhiteSpace($pair)) {
        $kv = $pair.Split("=", 2)
        $inlineQuery[$kv[0]] = if ($kv.Count -gt 1) { $kv[1] } else { "" }
      }
    }
  }
  foreach ($key in $query.Keys) {
    $inlineQuery[$key] = $query[$key]
  }

  $queryItems = @()
  foreach ($key in $inlineQuery.Keys) {
    $queryItems += @{ key = $key; value = [string]$inlineQuery[$key] }
  }

  $cleanPath = $pathOnly.TrimStart("/")
  $pathParts = if ($cleanPath.Length -eq 0) { @() } else { $cleanPath.Split("/") }
  $url = @{
    raw = "{{baseUrl}}/$cleanPath"
    host = @("{{baseUrl}}")
    path = $pathParts
  }
  if ($queryItems.Count -gt 0) {
    $url.query = $queryItems
  }
  return $url
}

function StatusTest([int]$status, [string]$extra = "") {
  $script = @"
pm.test("HTTP $status", function () {
  pm.response.to.have.status($status);
});

if (pm.response.headers.has("Content-Type") && pm.response.headers.get("Content-Type").includes("application/json")) {
  const body = pm.response.json();
  if (pm.response.code >= 400) {
    pm.test("ProblemDetail error shape", function () {
      pm.expect(body).to.have.property("status");
      pm.expect(body).to.have.property("title");
    });
  } else if (pm.response.code !== 204) {
    pm.test("Success response is enveloped", function () {
      pm.expect(body).to.have.property("data");
    });
  }
}
$extra
"@
  return @(@{ listen = "test"; script = @{ type = "text/javascript"; exec = $script.Split("`n") } })
}

function CaptureScript([int]$status, [string]$js) {
  return StatusTest $status @"

if (pm.response.code === $status && pm.response.headers.has("Content-Type") && pm.response.headers.get("Content-Type").includes("application/json")) {
  const json = pm.response.json();
  const data = json.data || json;
$js
}
"@
}

function Request(
  [string]$name,
  [string]$method,
  [string]$path,
  [int]$expected,
  [object]$body = $null,
  [string]$authToken = "{{accessToken}}",
  [hashtable]$query = @{},
  [array]$headers = @(),
  [string]$capture = "",
  [string]$description = ""
) {
  $requestHeaders = @(@{ key = "X-Request-Id"; value = "{{$guid}}" })
  if ($body -and $body.mode -eq "raw") {
    $requestHeaders += @{ key = "Content-Type"; value = "application/json" }
  }
  foreach ($header in $headers) {
    $requestHeaders += $header
  }

  $auth = @{ type = "bearer"; bearer = @(@{ key = "token"; value = $authToken; type = "string" }) }
  if ($authToken -eq "NOAUTH") {
    $auth = @{ type = "noauth" }
  }

  $req = @{
    method = $method
    header = $requestHeaders
    url = (UrlObject $path $query)
    auth = $auth
    description = $description
  }
  if ($body) {
    $req.body = $body
  }

  $events = if ([string]::IsNullOrWhiteSpace($capture)) { StatusTest $expected } else { CaptureScript $expected $capture }
  return @{ name = $name; request = $req; event = $events }
}

function ScenarioFolder([string]$folderName, [array]$requests) {
  return @{ name = $folderName; item = $requests }
}

$bodies = @{
  login = @{ email = "{{staffEmail}}"; password = "{{staffPassword}}" }
  invalidLogin = @{ email = "not-an-email"; password = "short" }
  refresh = @{ refreshToken = "{{refreshToken}}" }
  logout = @{ refreshToken = "{{refreshToken}}" }
  requestOtp = @{ phone = "{{parentPhone}}" }
  invalidPhone = @{ phone = "12345" }
  verifyOtp = @{ ticketId = "{{otpTicketId}}"; code = "{{otpCode}}" }
  invalidOtp = @{ ticketId = "{{otpTicketId}}"; code = "abc" }
  parentLogout = @{ token = "{{parentToken}}" }
  school = @{
    name = "Postman Demo School {{$timestamp}}"
    country = "EG"
    timezone = "Africa/Cairo"
    locale = "en-EG"
    subscriptionTier = "STANDARD"
    settings = @{
      defaultLanguage = "EN"
      quietHoursStart = "21:00:00"
      quietHoursEnd = "07:00:00"
      homeworkReminderEnabled = $true
      homeworkReminderTime = "18:00:00"
      feeReminderOffsetDays = @(-7, -1, 0)
      wabaPhoneNumberId = "{{wabaPhoneNumberId}}"
      smsFallbackEnabled = $true
      alertsRespectQuietHours = $true
      rosterDueByLocalTime = "09:00:00"
    }
  }
  invalidSchool = @{ name = ""; country = "Egypt"; timezone = ""; locale = ""; subscriptionTier = $null }
  settings = @{
    defaultLanguage = "EN"
    quietHoursStart = "21:00:00"
    quietHoursEnd = "07:00:00"
    homeworkReminderEnabled = $true
    homeworkReminderTime = "18:00:00"
    feeReminderOffsetDays = @(-7, -1, 0)
    wabaPhoneNumberId = "{{wabaPhoneNumberId}}"
    smsFallbackEnabled = $true
    alertsRespectQuietHours = $true
    rosterDueByLocalTime = "09:00:00"
  }
  invalidSettings = @{
    defaultLanguage = $null
    quietHoursStart = $null
    quietHoursEnd = "07:00:00"
    homeworkReminderEnabled = $true
    homeworkReminderTime = "18:00:00"
    feeReminderOffsetDays = @(-31)
    smsFallbackEnabled = $true
    alertsRespectQuietHours = $true
    rosterDueByLocalTime = $null
  }
  schoolAdminUser = @{ role = "SCHOOL_ADMIN"; name = "School Admin"; email = "{{schoolAdminEmail}}"; password = "{{schoolAdminPassword}}" }
  teacherUser = @{ role = "TEACHER"; name = "Teacher User"; email = "{{teacherEmail}}"; password = "{{teacherPassword}}" }
  parentUser = @{ role = "PARENT"; name = "Parent User"; phone = "{{parentPhone}}" }
  invalidUser = @{ role = $null; name = ""; email = "bad-email"; password = "short" }
  schoolClass = @{ name = "Grade 1 A"; gradeLevel = "1"; academicYear = "2026-2027"; homeroomTeacherId = "{{teacherUserId}}" }
  invalidClass = @{ name = ""; gradeLevel = ""; academicYear = "" }
  student = @{ fullName = "Demo Student"; dateOfBirth = "2018-09-01"; externalId = "STU-{{$timestamp}}" }
  invalidStudent = @{ fullName = ""; dateOfBirth = "2999-01-01"; externalId = "too-long" }
  updateStudent = @{ fullName = "Demo Student Updated"; dateOfBirth = "2018-09-01"; externalId = "STU-UPD"; status = "ACTIVE" }
  invalidUpdateStudent = @{ fullName = ""; status = $null }
  enroll = @{ studentId = "{{studentId}}" }
  invalidEnroll = @{ studentId = $null }
  assignTeacher = @{ teacherUserId = "{{teacherUserId}}" }
  invalidAssignTeacher = @{ teacherUserId = $null }
  parentLink = @{ parentUserId = "{{parentUserId}}"; studentId = "{{studentId}}"; relationship = "MOTHER"; primaryContact = $true }
  invalidParentLink = @{ parentUserId = $null; studentId = $null; relationship = $null; primaryContact = $true }
  announcementSchool = @{
    scopeType = "SCHOOL"
    language = "EN"
    body = "School-wide announcement from Postman."
    requiresAck = $true
  }
  announcementClass = @{
    scopeType = "CLASS"
    classId = "{{classId}}"
    language = "EN"
    body = "Class announcement from Postman."
    requiresAck = $true
  }
  invalidAnnouncement = @{ scopeType = "CLASS"; language = "EN"; body = ""; requiresAck = $false }
  invalidAnnouncementScope = @{ scopeType = "GRADE"; language = "EN"; body = "Missing grade level"; requiresAck = $false }
  markAttendance = @{ studentId = "{{studentId}}"; classId = "{{classId}}"; date = "{{attendanceDate}}"; status = "ABSENT" }
  invalidMarkAttendance = @{ studentId = $null; classId = $null; date = $null; status = $null }
  markAllPresent = @{ classId = "{{classId}}"; date = "{{attendanceDate}}" }
  invalidMarkAllPresent = @{ classId = $null; date = $null }
  parentResponse = @{ response = "Parent has been informed and will follow up." }
  invalidParentResponse = @{ response = "" }
  device = @{ platform = "ANDROID"; fcmToken = "{{fcmToken}}"; deviceId = "{{deviceId}}" }
  invalidDevice = @{ platform = $null; fcmToken = ""; deviceId = "" }
  webhook = @{
    object = "whatsapp_business_account"
    entry = @(@{
      id = "{{whatsappBusinessAccountId}}"
      changes = @(@{
        field = "messages"
        value = @{ statuses = @(@{ id = "{{whatsappMessageId}}"; status = "delivered"; timestamp = "1717171717"; recipient_id = "{{parentPhone}}" }) }
      })
    })
  }
}

$folders = @()

$folders += ScenarioFolder "Auth - Staff/Admin JWT" @(
  (Request "200 - Login" "POST" "/api/v1/auth/login" 200 (JsonBody $bodies.login) "NOAUTH" @{} @() @"
  if (data.accessToken) pm.environment.set("accessToken", data.accessToken);
  if (data.refreshToken) pm.environment.set("refreshToken", data.refreshToken);
"@ "Staff and platform admin email/password login."),
  (Request "401 - Invalid credentials" "POST" "/api/v1/auth/login" 401 (JsonBody @{ email = "{{staffEmail}}"; password = "wrong-password" }) "NOAUTH"),
  (Request "422 - Invalid login body" "POST" "/api/v1/auth/login" 422 (JsonBody $bodies.invalidLogin) "NOAUTH"),
  (Request "200 - Refresh token" "POST" "/api/v1/auth/refresh" 200 (JsonBody $bodies.refresh) "NOAUTH" @{} @() @"
  if (data.accessToken) pm.environment.set("accessToken", data.accessToken);
  if (data.refreshToken) pm.environment.set("refreshToken", data.refreshToken);
"@),
  (Request "401 - Refresh token invalid" "POST" "/api/v1/auth/refresh" 401 (JsonBody @{ refreshToken = "invalid-refresh-token" }) "NOAUTH"),
  (Request "422 - Refresh body missing" "POST" "/api/v1/auth/refresh" 422 (JsonBody @{}) "NOAUTH"),
  (Request "204 - Logout" "POST" "/api/v1/auth/logout" 204 (JsonBody $bodies.logout) "NOAUTH"),
  (Request "422 - Logout body missing" "POST" "/api/v1/auth/logout" 422 (JsonBody @{}) "NOAUTH")
)

$folders += ScenarioFolder "Parent Auth - OTP" @(
  (Request "200 - Request OTP" "POST" "/api/v1/parents/auth/request-otp" 200 (JsonBody $bodies.requestOtp) "NOAUTH" @{} @() @"
  if (data.ticketId) pm.environment.set("otpTicketId", data.ticketId);
"@),
  (Request "422 - Malformed phone number" "POST" "/api/v1/parents/auth/request-otp" 422 (JsonBody $bodies.invalidPhone) "NOAUTH"),
  (Request "429 - OTP rate limited" "POST" "/api/v1/parents/auth/request-otp" 429 (JsonBody $bodies.requestOtp) "NOAUTH" @{} @() "" "Run repeatedly against the same phone number until the rate limiter is hit."),
  (Request "200 - Verify OTP" "POST" "/api/v1/parents/auth/verify-otp" 200 (JsonBody $bodies.verifyOtp) "NOAUTH" @{} @() @"
  if (data.token) pm.environment.set("parentToken", data.token);
  if (data.schoolId) pm.environment.set("schoolId", data.schoolId);
"@),
  (Request "400 - Wrong or expired OTP" "POST" "/api/v1/parents/auth/verify-otp" 400 (JsonBody @{ ticketId = "{{otpTicketId}}"; code = "000000" }) "NOAUTH"),
  (Request "422 - Invalid OTP body" "POST" "/api/v1/parents/auth/verify-otp" 422 (JsonBody $bodies.invalidOtp) "NOAUTH"),
  (Request "204 - Parent logout" "POST" "/api/v1/parents/auth/logout" 204 (JsonBody $bodies.parentLogout) "NOAUTH"),
  (Request "422 - Parent logout body missing" "POST" "/api/v1/parents/auth/logout" 422 (JsonBody @{}) "NOAUTH")
)

$folders += ScenarioFolder "Schools - Tenant Management" @(
  (Request "201 - Create school" "POST" "/api/v1/schools" 201 (JsonBody $bodies.school) "{{superAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("schoolId", data.id);
"@),
  (Request "401 - Create school unauthenticated" "POST" "/api/v1/schools" 401 (JsonBody $bodies.school) "NOAUTH"),
  (Request "403 - Create school forbidden" "POST" "/api/v1/schools" 403 (JsonBody $bodies.school) "{{parentToken}}"),
  (Request "409 - Duplicate school slug/name" "POST" "/api/v1/schools" 409 (JsonBody $bodies.school) "{{superAdminToken}}" @{} @() "" "Run after creating the same school/slug once."),
  (Request "422 - Invalid school body" "POST" "/api/v1/schools" 422 (JsonBody $bodies.invalidSchool) "{{superAdminToken}}"),
  (Request "200 - List schools" "GET" "/api/v1/schools" 200 $null "{{superAdminToken}}" @{ status = "ACTIVE"; page = "0"; size = "20" }),
  (Request "401 - List schools unauthenticated" "GET" "/api/v1/schools" 401 $null "NOAUTH"),
  (Request "403 - List schools forbidden" "GET" "/api/v1/schools" 403 $null "{{parentToken}}"),
  (Request "200 - Get school" "GET" "/api/v1/schools/{{schoolId}}" 200 $null "{{superAdminToken}}"),
  (Request "404 - Get school not found" "GET" "/api/v1/schools/$missingUuid" 404 $null "{{superAdminToken}}"),
  (Request "200 - Get settings" "GET" "/api/v1/schools/{{schoolId}}/settings" 200 $null "{{superAdminToken}}"),
  (Request "404 - Get settings school not found" "GET" "/api/v1/schools/$missingUuid/settings" 404 $null "{{superAdminToken}}"),
  (Request "200 - Update settings" "PUT" "/api/v1/schools/{{schoolId}}/settings" 200 (JsonBody $bodies.settings) "{{superAdminToken}}"),
  (Request "422 - Update settings invalid" "PUT" "/api/v1/schools/{{schoolId}}/settings" 422 (JsonBody $bodies.invalidSettings) "{{superAdminToken}}"),
  (Request "204 - Suspend school" "POST" "/api/v1/schools/{{schoolId}}/suspend" 204 $null "{{superAdminToken}}"),
  (Request "409 - Suspend already suspended" "POST" "/api/v1/schools/{{schoolId}}/suspend" 409 $null "{{superAdminToken}}"),
  (Request "204 - Reactivate school" "POST" "/api/v1/schools/{{schoolId}}/reactivate" 204 $null "{{superAdminToken}}"),
  (Request "409 - Reactivate active school" "POST" "/api/v1/schools/{{schoolId}}/reactivate" 409 $null "{{superAdminToken}}")
)

$folders += ScenarioFolder "School Users" @(
  (Request "201 - Create school admin user" "POST" "/api/v1/schools/{{schoolId}}/users" 201 (JsonBody $bodies.schoolAdminUser) "{{superAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("schoolAdminUserId", data.id);
"@),
  (Request "201 - Create teacher user" "POST" "/api/v1/schools/{{schoolId}}/users" 201 (JsonBody $bodies.teacherUser) "{{superAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("teacherUserId", data.id);
"@),
  (Request "201 - Create parent user" "POST" "/api/v1/schools/{{schoolId}}/users" 201 (JsonBody $bodies.parentUser) "{{superAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("parentUserId", data.id);
"@),
  (Request "401 - Create user unauthenticated" "POST" "/api/v1/schools/{{schoolId}}/users" 401 (JsonBody $bodies.schoolAdminUser) "NOAUTH"),
  (Request "403 - Create user forbidden" "POST" "/api/v1/schools/{{schoolId}}/users" 403 (JsonBody $bodies.schoolAdminUser) "{{schoolAdminToken}}"),
  (Request "404 - Create user school not found" "POST" "/api/v1/schools/$missingUuid/users" 404 (JsonBody $bodies.schoolAdminUser) "{{superAdminToken}}"),
  (Request "409 - User email or phone duplicate" "POST" "/api/v1/schools/{{schoolId}}/users" 409 (JsonBody $bodies.schoolAdminUser) "{{superAdminToken}}" @{} @() "" "Run after creating a user with the same email/phone."),
  (Request "422 - Invalid user body" "POST" "/api/v1/schools/{{schoolId}}/users" 422 (JsonBody $bodies.invalidUser) "{{superAdminToken}}"),
  (Request "200 - List users" "GET" "/api/v1/schools/{{schoolId}}/users" 200 $null "{{superAdminToken}}" @{ page = "0"; size = "20" }),
  (Request "404 - List users school not found" "GET" "/api/v1/schools/$missingUuid/users" 404 $null "{{superAdminToken}}"),
  (Request "200 - Get user" "GET" "/api/v1/schools/{{schoolId}}/users/{{schoolAdminUserId}}" 200 $null "{{superAdminToken}}"),
  (Request "404 - Get user not found" "GET" "/api/v1/schools/{{schoolId}}/users/$missingUuid" 404 $null "{{superAdminToken}}")
)

$folders += ScenarioFolder "Classes" @(
  (Request "201 - Create class" "POST" "/api/v1/classes" 201 (JsonBody $bodies.schoolClass) "{{schoolAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("classId", data.id);
"@),
  (Request "401 - Create class unauthenticated" "POST" "/api/v1/classes" 401 (JsonBody $bodies.schoolClass) "NOAUTH"),
  (Request "403 - Create class forbidden" "POST" "/api/v1/classes" 403 (JsonBody $bodies.schoolClass) "{{parentToken}}"),
  (Request "422 - Invalid class body" "POST" "/api/v1/classes" 422 (JsonBody $bodies.invalidClass) "{{schoolAdminToken}}"),
  (Request "200 - List classes" "GET" "/api/v1/classes" 200 $null "{{schoolAdminToken}}" @{ page = "0"; size = "20" }),
  (Request "200 - Get class" "GET" "/api/v1/classes/{{classId}}" 200 $null "{{schoolAdminToken}}"),
  (Request "404 - Get class not found" "GET" "/api/v1/classes/$missingUuid" 404 $null "{{schoolAdminToken}}"),
  (Request "200 - Update class" "PATCH" "/api/v1/classes/{{classId}}" 200 (JsonBody $bodies.schoolClass) "{{schoolAdminToken}}"),
  (Request "422 - Update class invalid" "PATCH" "/api/v1/classes/{{classId}}" 422 (JsonBody $bodies.invalidClass) "{{schoolAdminToken}}"),
  (Request "204 - Delete class" "DELETE" "/api/v1/classes/{{classId}}" 204 $null "{{schoolAdminToken}}"),
  (Request "404 - Delete class not found" "DELETE" "/api/v1/classes/$missingUuid" 404 $null "{{schoolAdminToken}}")
)

$folders += ScenarioFolder "Students" @(
  (Request "201 - Create student" "POST" "/api/v1/students" 201 (JsonBody $bodies.student) "{{schoolAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("studentId", data.id);
"@),
  (Request "401 - Create student unauthenticated" "POST" "/api/v1/students" 401 (JsonBody $bodies.student) "NOAUTH"),
  (Request "403 - Create student forbidden" "POST" "/api/v1/students" 403 (JsonBody $bodies.student) "{{parentToken}}"),
  (Request "422 - Invalid student body" "POST" "/api/v1/students" 422 (JsonBody $bodies.invalidStudent) "{{schoolAdminToken}}"),
  (Request "200 - List students" "GET" "/api/v1/students" 200 $null "{{schoolAdminToken}}" @{ page = "0"; size = "20" }),
  (Request "200 - Get student" "GET" "/api/v1/students/{{studentId}}" 200 $null "{{schoolAdminToken}}"),
  (Request "404 - Get student not found" "GET" "/api/v1/students/$missingUuid" 404 $null "{{schoolAdminToken}}"),
  (Request "200 - Update student" "PATCH" "/api/v1/students/{{studentId}}" 200 (JsonBody $bodies.updateStudent) "{{schoolAdminToken}}"),
  (Request "422 - Update student invalid" "PATCH" "/api/v1/students/{{studentId}}" 422 (JsonBody $bodies.invalidUpdateStudent) "{{schoolAdminToken}}"),
  (Request "200 - Bulk import CSV" "POST" "/api/v1/students:bulk-import" 200 (FormDataBody @(@{ key = "file"; type = "file"; src = "" })) "{{schoolAdminToken}}" @{} @() "" "Attach a UTF-8 CSV file with header externalId,fullName,dateOfBirth,className."),
  (Request "422 - Bulk import file missing" "POST" "/api/v1/students:bulk-import" 422 (FormDataBody @()) "{{schoolAdminToken}}"),
  (Request "204 - Delete student" "DELETE" "/api/v1/students/{{studentId}}" 204 $null "{{schoolAdminToken}}"),
  (Request "404 - Delete student not found" "DELETE" "/api/v1/students/$missingUuid" 404 $null "{{schoolAdminToken}}")
)

$folders += ScenarioFolder "Enrollments" @(
  (Request "201 - Enroll student" "POST" "/api/v1/classes/{{classId}}/enrollments" 201 (JsonBody $bodies.enroll) "{{schoolAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("enrollmentId", data.id);
"@),
  (Request "401 - Enroll unauthenticated" "POST" "/api/v1/classes/{{classId}}/enrollments" 401 (JsonBody $bodies.enroll) "NOAUTH"),
  (Request "403 - Enroll forbidden" "POST" "/api/v1/classes/{{classId}}/enrollments" 403 (JsonBody $bodies.enroll) "{{teacherToken}}"),
  (Request "404 - Enroll class or student not found" "POST" "/api/v1/classes/$missingUuid/enrollments" 404 (JsonBody $bodies.enroll) "{{schoolAdminToken}}"),
  (Request "409 - Duplicate enrollment" "POST" "/api/v1/classes/{{classId}}/enrollments" 409 (JsonBody $bodies.enroll) "{{schoolAdminToken}}" @{} @() "" "Run after the same student is already enrolled in the class."),
  (Request "422 - Enroll invalid body" "POST" "/api/v1/classes/{{classId}}/enrollments" 422 (JsonBody $bodies.invalidEnroll) "{{schoolAdminToken}}"),
  (Request "200 - List class enrollments" "GET" "/api/v1/classes/{{classId}}/enrollments" 200 $null "{{schoolAdminToken}}"),
  (Request "403 - List enrollments teacher not assigned" "GET" "/api/v1/classes/{{classId}}/enrollments" 403 $null "{{teacherToken}}"),
  (Request "404 - List enrollments class not found" "GET" "/api/v1/classes/$missingUuid/enrollments" 404 $null "{{schoolAdminToken}}"),
  (Request "204 - Remove enrollment" "DELETE" "/api/v1/enrollments/{{enrollmentId}}" 204 $null "{{schoolAdminToken}}"),
  (Request "404 - Remove enrollment not found" "DELETE" "/api/v1/enrollments/$missingUuid" 404 $null "{{schoolAdminToken}}")
)

$folders += ScenarioFolder "Teacher Assignments" @(
  (Request "201 - Assign teacher" "POST" "/api/v1/classes/{{classId}}/teachers" 201 (JsonBody $bodies.assignTeacher) "{{schoolAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("teacherAssignmentId", data.id);
"@),
  (Request "404 - Assign class or teacher not found" "POST" "/api/v1/classes/$missingUuid/teachers" 404 (JsonBody $bodies.assignTeacher) "{{schoolAdminToken}}"),
  (Request "409 - Duplicate teacher assignment" "POST" "/api/v1/classes/{{classId}}/teachers" 409 (JsonBody $bodies.assignTeacher) "{{schoolAdminToken}}" @{} @() "" "Run after assigning the same teacher to the class."),
  (Request "422 - Assign teacher invalid body" "POST" "/api/v1/classes/{{classId}}/teachers" 422 (JsonBody $bodies.invalidAssignTeacher) "{{schoolAdminToken}}"),
  (Request "200 - List class teachers" "GET" "/api/v1/classes/{{classId}}/teachers" 200 $null "{{schoolAdminToken}}"),
  (Request "404 - List class teachers class not found" "GET" "/api/v1/classes/$missingUuid/teachers" 404 $null "{{schoolAdminToken}}"),
  (Request "204 - Remove teacher assignment" "DELETE" "/api/v1/teacher-assignments/{{teacherAssignmentId}}" 204 $null "{{schoolAdminToken}}"),
  (Request "404 - Remove teacher assignment not found" "DELETE" "/api/v1/teacher-assignments/$missingUuid" 404 $null "{{schoolAdminToken}}")
)

$folders += ScenarioFolder "Parent-Student Links" @(
  (Request "201 - Create parent link" "POST" "/api/v1/parent-links" 201 (JsonBody $bodies.parentLink) "{{schoolAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("parentLinkId", data.id);
"@),
  (Request "404 - Create link parent or student not found" "POST" "/api/v1/parent-links" 404 (JsonBody @{ parentUserId = $missingUuid; studentId = "{{studentId}}"; relationship = "MOTHER"; primaryContact = $true }) "{{schoolAdminToken}}"),
  (Request "409 - Duplicate parent link" "POST" "/api/v1/parent-links" 409 (JsonBody $bodies.parentLink) "{{schoolAdminToken}}" @{} @() "" "Run after creating the same parent-student link."),
  (Request "422 - Create parent link invalid body" "POST" "/api/v1/parent-links" 422 (JsonBody $bodies.invalidParentLink) "{{schoolAdminToken}}"),
  (Request "200 - List links by student" "GET" "/api/v1/parent-links/student/{{studentId}}" 200 $null "{{schoolAdminToken}}"),
  (Request "404 - List links student not found" "GET" "/api/v1/parent-links/student/$missingUuid" 404 $null "{{schoolAdminToken}}"),
  (Request "204 - Remove parent link" "DELETE" "/api/v1/parent-links/{{parentLinkId}}" 204 $null "{{schoolAdminToken}}"),
  (Request "404 - Remove parent link not found" "DELETE" "/api/v1/parent-links/$missingUuid" 404 $null "{{schoolAdminToken}}")
)

$folders += ScenarioFolder "Parent Portal" @(
  (Request "200 - List my children" "GET" "/api/v1/parents/me/children" 200 $null "{{parentToken}}"),
  (Request "401 - Children unauthenticated" "GET" "/api/v1/parents/me/children" 401 $null "NOAUTH"),
  (Request "403 - Children forbidden" "GET" "/api/v1/parents/me/children" 403 $null "{{schoolAdminToken}}")
)

$folders += ScenarioFolder "Announcements" @(
  (Request "201 - Create school announcement" "POST" "/api/v1/announcements" 201 (JsonBody $bodies.announcementSchool) "{{schoolAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("announcementId", data.id);
"@),
  (Request "201 - Create class announcement as teacher" "POST" "/api/v1/announcements" 201 (JsonBody $bodies.announcementClass) "{{teacherToken}}"),
  (Request "401 - Create announcement unauthenticated" "POST" "/api/v1/announcements" 401 (JsonBody $bodies.announcementSchool) "NOAUTH"),
  (Request "403 - Create announcement forbidden scope" "POST" "/api/v1/announcements" 403 (JsonBody $bodies.announcementSchool) "{{teacherToken}}"),
  (Request "422 - Invalid announcement body" "POST" "/api/v1/announcements" 422 (JsonBody $bodies.invalidAnnouncement) "{{schoolAdminToken}}"),
  (Request "422 - Invalid announcement scope combination" "POST" "/api/v1/announcements" 422 (JsonBody $bodies.invalidAnnouncementScope) "{{schoolAdminToken}}"),
  (Request "200 - List announcements" "GET" "/api/v1/announcements" 200 $null "{{schoolAdminToken}}" @{ status = "SENT"; page = "0"; size = "20" }),
  (Request "200 - Get announcement" "GET" "/api/v1/announcements/{{announcementId}}" 200 $null "{{schoolAdminToken}}"),
  (Request "403 - Get announcement not sender" "GET" "/api/v1/announcements/{{announcementId}}" 403 $null "{{teacherToken}}"),
  (Request "404 - Get announcement not found" "GET" "/api/v1/announcements/$missingUuid" 404 $null "{{schoolAdminToken}}"),
  (Request "200 - Recall announcement" "POST" "/api/v1/announcements/{{announcementId}}/recall" 200 $null "{{schoolAdminToken}}"),
  (Request "409 - Recall already recalled" "POST" "/api/v1/announcements/{{announcementId}}/recall" 409 $null "{{schoolAdminToken}}"),
  (Request "200 - List announcement recipients" "GET" "/api/v1/announcements/{{announcementId}}/recipients" 200 $null "{{schoolAdminToken}}" @{ page = "0"; size = "50" }),
  (Request "204 - Acknowledge announcement" "POST" "/api/v1/announcements/{{announcementId}}/acknowledge" 204 $null "{{parentToken}}"),
  (Request "403 - Acknowledge non-recipient" "POST" "/api/v1/announcements/{{announcementId}}/acknowledge" 403 $null "{{parentToken}}"),
  (Request "404 - Acknowledge announcement not found" "POST" "/api/v1/announcements/$missingUuid/acknowledge" 404 $null "{{parentToken}}")
)

$folders += ScenarioFolder "Attendance" @(
  (Request "200 - Mark attendance" "POST" "/api/v1/attendance/mark" 200 (JsonBody $bodies.markAttendance) "{{schoolAdminToken}}" @{} @() @"
  if (data.id) pm.environment.set("attendanceRecordId", data.id);
"@),
  (Request "403 - Mark attendance teacher not assigned" "POST" "/api/v1/attendance/mark" 403 (JsonBody $bodies.markAttendance) "{{teacherToken}}"),
  (Request "422 - Mark attendance invalid body" "POST" "/api/v1/attendance/mark" 422 (JsonBody $bodies.invalidMarkAttendance) "{{schoolAdminToken}}"),
  (Request "200 - Mark all present" "POST" "/api/v1/attendance/mark-all-present" 200 (JsonBody $bodies.markAllPresent) "{{schoolAdminToken}}"),
  (Request "422 - Mark all present invalid body" "POST" "/api/v1/attendance/mark-all-present" 422 (JsonBody $bodies.invalidMarkAllPresent) "{{schoolAdminToken}}"),
  (Request "200 - Get roster" "GET" "/api/v1/attendance/roster" 200 $null "{{schoolAdminToken}}" @{ classId = "{{classId}}"; date = "{{attendanceDate}}" }),
  (Request "404 - Get roster class not found" "GET" "/api/v1/attendance/roster" 404 $null "{{schoolAdminToken}}" @{ classId = $missingUuid; date = "{{attendanceDate}}" }),
  (Request "200 - Get history" "GET" "/api/v1/attendance/history" 200 $null "{{schoolAdminToken}}" @{ studentId = "{{studentId}}"; from = "{{attendanceDate}}"; to = "{{attendanceDate}}" }),
  (Request "404 - Get history student not found" "GET" "/api/v1/attendance/history" 404 $null "{{parentToken}}" @{ studentId = $missingUuid; from = "{{attendanceDate}}"; to = "{{attendanceDate}}" }),
  (Request "200 - Parent response" "POST" "/api/v1/attendance/{{attendanceRecordId}}/parent-response" 200 (JsonBody $bodies.parentResponse) "{{parentToken}}"),
  (Request "403 - Parent response forbidden role" "POST" "/api/v1/attendance/{{attendanceRecordId}}/parent-response" 403 (JsonBody $bodies.parentResponse) "{{schoolAdminToken}}"),
  (Request "404 - Parent response record not found" "POST" "/api/v1/attendance/$missingUuid/parent-response" 404 (JsonBody $bodies.parentResponse) "{{parentToken}}"),
  (Request "422 - Parent response invalid body" "POST" "/api/v1/attendance/{{attendanceRecordId}}/parent-response" 422 (JsonBody $bodies.invalidParentResponse) "{{parentToken}}")
)

$folders += ScenarioFolder "Devices" @(
  (Request "200 - Register device" "POST" "/api/v1/devices/register" 200 (JsonBody $bodies.device) "{{accessToken}}" @{} @() @"
  if (data.deviceId) pm.environment.set("deviceId", data.deviceId);
"@),
  (Request "401 - Register device unauthenticated" "POST" "/api/v1/devices/register" 401 (JsonBody $bodies.device) "NOAUTH"),
  (Request "422 - Register device invalid body" "POST" "/api/v1/devices/register" 422 (JsonBody $bodies.invalidDevice) "{{accessToken}}"),
  (Request "204 - Deregister device" "DELETE" "/api/v1/devices/{{deviceId}}" 204 $null "{{accessToken}}"),
  (Request "404 - Deregister device not found" "DELETE" "/api/v1/devices/unknown-device" 404 $null "{{accessToken}}")
)

$folders += ScenarioFolder "WhatsApp Webhook" @(
  (Request "200 - Verify subscription" "GET" "/integrations/whatsapp/webhook" 200 $null "NOAUTH" @{ "hub.mode" = "subscribe"; "hub.verify_token" = "{{whatsappVerifyToken}}"; "hub.challenge" = "challenge-value" } @() "" "Returns the challenge as plain text when the verify token matches."),
  (Request "403 - Verify subscription invalid token" "GET" "/integrations/whatsapp/webhook" 403 $null "NOAUTH" @{ "hub.mode" = "subscribe"; "hub.verify_token" = "wrong"; "hub.challenge" = "challenge-value" }),
  (Request "200 - Receive delivery status" "POST" "/integrations/whatsapp/webhook" 200 (JsonBody $bodies.webhook) "NOAUTH" @{} @(@{ key = "X-Hub-Signature-256"; value = "{{whatsappSignature}}" }) "" "Set whatsappSignature to sha256=<hex HMAC> for the exact raw body using WHATSAPP_APP_SECRET."),
  (Request "401 - Receive delivery status invalid signature" "POST" "/integrations/whatsapp/webhook" 401 (JsonBody $bodies.webhook) "NOAUTH" @{} @(@{ key = "X-Hub-Signature-256"; value = "sha256=invalid" }))
)

$collection = @{
  info = @{
    _postman_id = "7b9cc5ef-7c0d-4c93-b963-8da8d5b8b001"
    name = "SchoolBridge API - Complete Scenarios"
    description = @"
Generated from the Spring Boot controllers in this repository.

Response contract:
- 2xx JSON responses are wrapped as { data, meta }.
- 204 responses have no body.
- 4xx/5xx responses use RFC 7807 ProblemDetail.

Run setup requests first:
1. Auth - Staff/Admin JWT / 200 - Login
2. Schools / 201 - Create school
3. School Users requests
4. Login as school admin or teacher, then copy tokens into schoolAdminToken / teacherToken.
5. Parent Auth flow for parentToken.
"@
    schema = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
  }
  auth = @{ type = "bearer"; bearer = @(@{ key = "token"; value = "{{accessToken}}"; type = "string" }) }
  event = @(
    @{
      listen = "prerequest"
      script = @{
        type = "text/javascript"
        exec = @(
          "if (!pm.environment.get('attendanceDate')) {",
          "  pm.environment.set('attendanceDate', new Date().toISOString().slice(0, 10));",
          "}"
        )
      }
    }
  )
  variable = @(
    @{ key = "baseUrl"; value = "http://localhost:8080" }
  )
  item = $folders
}

$environment = @{
  id = "a2cc9d3f-f329-45b3-a016-67167f10e001"
  name = "SchoolBridge Local"
  values = @(
    @{ key = "baseUrl"; value = "http://localhost:8080"; type = "default"; enabled = $true },
    @{ key = "accessToken"; value = ""; type = "secret"; enabled = $true },
    @{ key = "superAdminToken"; value = ""; type = "secret"; enabled = $true },
    @{ key = "schoolAdminToken"; value = ""; type = "secret"; enabled = $true },
    @{ key = "teacherToken"; value = ""; type = "secret"; enabled = $true },
    @{ key = "parentToken"; value = ""; type = "secret"; enabled = $true },
    @{ key = "refreshToken"; value = ""; type = "secret"; enabled = $true },
    @{ key = "staffEmail"; value = "admin@example.com"; type = "default"; enabled = $true },
    @{ key = "staffPassword"; value = "Password123!"; type = "secret"; enabled = $true },
    @{ key = "schoolAdminEmail"; value = "school-admin@example.com"; type = "default"; enabled = $true },
    @{ key = "schoolAdminPassword"; value = "Password123!"; type = "secret"; enabled = $true },
    @{ key = "teacherEmail"; value = "teacher@example.com"; type = "default"; enabled = $true },
    @{ key = "teacherPassword"; value = "Password123!"; type = "secret"; enabled = $true },
    @{ key = "parentPhone"; value = "+201000000000"; type = "default"; enabled = $true },
    @{ key = "otpTicketId"; value = ""; type = "default"; enabled = $true },
    @{ key = "otpCode"; value = "000000"; type = "default"; enabled = $true },
    @{ key = "schoolId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "schoolAdminUserId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "teacherUserId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "parentUserId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "classId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "studentId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "enrollmentId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "teacherAssignmentId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "parentLinkId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "announcementId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "attendanceRecordId"; value = $sampleUuid; type = "default"; enabled = $true },
    @{ key = "attendanceDate"; value = "2026-06-05"; type = "default"; enabled = $true },
    @{ key = "deviceId"; value = "android-demo-device"; type = "default"; enabled = $true },
    @{ key = "fcmToken"; value = "demo-fcm-token"; type = "secret"; enabled = $true },
    @{ key = "wabaPhoneNumberId"; value = ""; type = "default"; enabled = $true },
    @{ key = "whatsappVerifyToken"; value = ""; type = "secret"; enabled = $true },
    @{ key = "whatsappSignature"; value = "sha256=<computed-hmac>"; type = "secret"; enabled = $true },
    @{ key = "whatsappBusinessAccountId"; value = "waba-id"; type = "default"; enabled = $true },
    @{ key = "whatsappMessageId"; value = "wamid.demo"; type = "default"; enabled = $true }
  )
  _postman_variable_scope = "environment"
  _postman_exported_using = "Codex"
}

$collectionPath = Join-Path $OutDir "SchoolBridge.postman_collection.json"
$environmentPath = Join-Path $OutDir "SchoolBridge.local.postman_environment.json"

$collection | ConvertTo-Json -Depth 100 | Set-Content -Path $collectionPath -Encoding UTF8
$environment | ConvertTo-Json -Depth 100 | Set-Content -Path $environmentPath -Encoding UTF8

Write-Host "Wrote $collectionPath"
Write-Host "Wrote $environmentPath"
