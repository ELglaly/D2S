---
name: schoolbridge-i18n-message
description: Add or change a user-facing message in SchoolBridge with correct ar/en parity. Use whenever a new error message, notification template, or user-visible string is introduced.
---

# SchoolBridge: i18n message parity

Hard rule (`.claude/CLAUDE.md`): i18n ar + en on **all** user-facing
messages, no exceptions for "temporary" or internal-sounding strings that
might surface to a user or parent.

## Steps

1. Add the key to **both**
   `src/main/resources/messages_en.properties` and
   `src/main/resources/messages_ar.properties` in the same change — never
   one without the other.
2. Use the same key name in both files. Keep key parity exact — the project
   has previously been verified at exact key-count parity between the two
   files; a mismatch is a regression, not a style issue.
3. For templated messages (placeholders), keep placeholder syntax
   consistent between `en`/`ar` (same `{0}`/named-placeholder style used
   elsewhere in the file) so the same calling code formats both.
4. If the message is a multi-line template with literal `\n` and gets
   applied via `.formatted(...)`, switch to `template.replace("{x}", v)`
   instead — SpotBugs flags `VA_FORMAT_STRING_USES_NEWLINE` on the
   `.formatted()` form (`docs/COMMON_MISTAKES.md` #10).
5. Reference the key from a `MessageSource`/resolver call, not a hardcoded
   literal string, so the locale actually switches per request.

## Verify

Before finishing: confirm both properties files still have the same key
count and every new key appears in both. A quick check:

```shell
diff <(sed -n 's/=.*//p' src/main/resources/messages_en.properties | sort) \
     <(sed -n 's/=.*//p' src/main/resources/messages_ar.properties | sort)
```

Empty diff = parity holds.
