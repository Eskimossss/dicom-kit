# DICOM Kit — MWL conformance notes, 1.6.0-beta.1

This describes the implemented subset and measured behavior; it is not a claim of certification on all modalities. See [audit fixes and evidence](STAGE_7_AUDIT_FIXES.md).

## Beta.1 publication and Worklist state

This is a testing Beta Pre-release, not a final stable version.
- **Initial unseeded state:** A fresh installation does not automatically seed demo patients, appointments, or station profiles (`autoSeedIfEmpty = false`). Manual creation is available via the synthetic generator action.
- **Update preservation:** Installing over previous versions preserves all existing databases, snapshots, and PACS profiles.
- **Appointment identifiers:** `Accession Number` (0008,0050), `Requested Procedure ID` (0040,1001), and `Scheduled Procedure Step ID` (0040,0009) conform to VR SH (up to 16 characters). `Study Instance UID` (0020,000D) conforms to VR UI (up to 64 characters). Generated values adhere strictly to DICOM VR constraints. Automatic fallback generation populates blank identifiers on save.
- **Study descriptions:** Standard Procedure Descriptions from DICOM PS3.3 / PS3.4 are suggested per modality (CR, DX, MG, CT, MR, US, etc.), while preserving manual input (VR LO, max 64 characters).

## Alpha.5 publication and Storage interoperability

This is a testing Pre-release, not stable or fully accepted MWL support. Primary manual testing on Xiaomi Pad 6 was reported complete by the user; the existing MWL acceptance matrix and physical-device performance comparison remain applicable. The publication retains the current Worklist SCP/SCU implementation.

Storage SCP additionally accepts digit-and-dot Study/Series/SOP Instance UIDs of 1–64 characters with leading-zero components as an interoperability exception, logs the affected tag and preserves the literal UID, Dataset and Pixel Data. This does not declare those UIDs standard-conformant. Unsafe or missing identifiers fail; a command/Dataset SOP mismatch returns A900. Distinct literal UIDs remain separate, verified storage and collision protection are retained. See [Storage policy](../README.md) and [acceptance checks](ANDROID_ACCEPTANCE_TEST_PLAN.md). These changes do not alter MWL matching, UID generation, character-set negotiation or persistent snapshot formats.

## Final audit corrections (alpha.5)

For MWL SPS Date/Time, the special continuous interval rule of [PS3.4 K.6, Table K.6-1](https://dicom.nema.org/medical/dicom/current/output/chtml/part04/sect_K.6.html) applies when both keys specify ranges. Endpoint syntax is checked independently, but their order is checked as combined date-time boundaries. Thus `20261021-20261022` / `230000-010000` includes `20261022 003000`. A descending standalone TM range and a descending combined interval on the same date remain invalid (A900), consistent with the standalone TM limits in [C.2.2.2.5](https://dicom.nema.org/medical/dicom/current/output/chtml/part04/sect_C.2.2.2.5.html). Open date boundaries remain unbounded; an omitted time at a finite date boundary retains the existing beginning/end-of-day behavior. Partial TM precision, local time interpretation and lack of timezone negotiation are unchanged. No rule for ordinary Query/Retrieve combined matching is newly negotiated or claimed.

Accession Number (0008,0050) is a single SH value. Validation rejects a literal value separator (`\`), control characters and more than 16 characters before persistence or Dataset construction; empty Type 2 values and valid Unicode identifiers are retained without rewriting. DICOM code-extension escapes are produced by the charset encoder, not accepted as literal control characters in an entered identifier. Invalid historical values are not automatically truncated or rewritten; they must be corrected before transmission.

Patient records own PN and Patient ID. Import synchronizes those denormalized fields in every linked appointment, including appointments absent from the import, in the same atomic snapshot commit. Other appointment fields and omitted appointments remain intact; a failed replacement leaves both published memory and disk unchanged.

Version-1 snapshot loading validates the version, required array types and record identities before publishing either collection. An unreadable/incomplete/unsupported snapshot is never substituted with empty arrays or legacy files. The original files and last readable collections remain intact; writes and JSON export are blocked, the Worklist UI displays a diagnostic, and an SCP snapshot attempt fails (C000), never an empty successful worklist. An explicitly restored valid file can be reloaded; no automatic repair, deletion or destructive migration is performed. Clinical validation of historical fields is still deferred to edit/transmission rather than rewriting records on load.

SCU profile write I/O failures leave the committed profile, selection and editor draft intact. The UI displays the error and permits retry. These changes do not change applicationId, signing, Storage behavior, the network whitelist or character-set policy. Test evidence, initial failures and remaining acceptance limits are recorded in the existing [acceptance document](STAGE_7_WORKLIST_ACCEPTANCE.md).

## Alpha.4 patient-name and UI boundaries

The local patientName remains the same raw DICOM PN string in the version-1 atomic snapshot. There is no migration or data rewrite on load. Three basic inputs represent family, given and middle name in that order, separated by `^`. Existing values with a fourth/fifth component, any `=` representation group or an ambiguous unseparated name containing spaces open in the raw editor. No automatic space-to-component conversion occurs. Merely opening, switching modes and saving an existing name retains its exact string, including empty/trailing components and whitespace. Explicit edits in the basic inputs form a new PN; explicit raw edits remain subject to the existing PN validation (up to three groups, five components per group and 64 characters per group). Lists use display-only PN formatting; raw values remain available in the editor and result details.

Tests cover exact JSON/repository round trips, linked appointments and Patient ID `SYNTH-PAT-0001`, all five components/three groups, Cyrillic/Latin/mixed names, UTF-8 `ISO_IR 192` and Cyrillic `ISO_IR 144` DICOM encoding. The DICOM library removes insignificant outer space padding when decoding a wire PN; local storage does not. ISO_IR 144 cannot represent Japanese characters; the existing lossless charset safeguards are retained. Invalid historical entities are preserved for correction, with no silent truncation or automatic repair.

Birth-date UI saves empty or Gregorian DA `YYYYMMDD`, uses UTC calendar-day conversion and rejects newly entered future dates. A pre-existing date is not silently cleared or rewritten by opening the patient editor; unchanged historical values still pass through the existing repository validation. Year and month can be selected directly; an exact DA input is also available.

The Modality picker contains common standard Defined Terms from [PS3.3 C.7.3.1.1.1](https://dicom.nema.org/medical/dicom/current/output/chtml/part03/sect_C.7.3.html#sect_C.7.3.1.1.1). Manual codes retain the same CS validation. This picker does not declare or add SOP Class support. Context help uses the existing [PS3.5 VR definitions](https://dicom.nema.org/medical/dicom/current/output/chtml/part05/sect_6.2.html). The wire query, matching engine, charset negotiation and Dataset are unchanged. Result summaries show selected fields; details retain the raw PN, other model fields and the existing recursive Dataset inspector with Sequence/Items (its existing display depth limit is unchanged).

## Alpha.3 scheduling UI and persistence

An appointment explicitly selects a local patient by internal identity. Selecting an existing patient does not create a copy. Patients without appointments are editable from the patient list. Creating a patient inside the appointment editor selects that patient; only saving the appointment changes its link. The repository validates existence and refreshes its denormalized PN/ID from the authoritative patient in the same atomic snapshot. Existing snapshots and legacy links are retained on load; there is no destructive migration or automatic reassignment.

Station profiles are independent local settings in `mwl_station_profiles.json`. They contain a display name, Modality, Scheduled Station AE Title, Scheduled Station Name and optional Location. Applying/saving a profile copies values to the current draft; existing appointments are not rewritten. IP and port are not required. Profiles do not introduce Calling AE based routing; the existing Matching Keys and optional association whitelist retain their behavior. A corrupt settings file is preserved and further writes are blocked with a visible error.

| UI field | Tag in SPS Item | VR / value limit |
|---|---|---|
| AE Title аппарата | (0040,0001) | AE: 16 bytes, allowed ASCII repertoire |
| Название кабинета / станции | (0040,0010) | SH: 16 characters |
| Место проведения исследования | (0040,0011) | SH: 16 characters |
| Description | (0040,0007), (0032,1060) | LO: 64 characters |

The UI represents one station value per appointment. Limits and meanings were checked against [PS3.5 VR](https://dicom.nema.org/medical/dicom/current/output/chtml/part05/sect_6.2.html) and [PS3.3 SPS module](https://dicom.nema.org/medical/dicom/current/output/chtml/part03/sect_C.4.10.html). PN retains its component/group validation rather than using a universal 16-character limit.

The SCU calendar selects Today, Tomorrow, one explicit date, a closed ordered range or Any. Calendar dates are converted to DA without timezone shifts (`YYYYMMDD` or `YYYYMMDD-YYYYMMDD`). Empty custom dates and incomplete/reversed UI ranges block query submission. The DA/TM Matching Engine, open range support for protocol clients and continuous DA/TM interval semantics described below are unchanged.

## Network and association

- MWL FIND SCU/SCP: `1.2.840.10008.5.1.4.31`. Verification: `1.2.840.10008.1.1`.
- Implicit VR Little Endian (`1.2.840.10008.1.2`) and Explicit VR Little Endian (`1.2.840.10008.1.2.1`).
- Default SCP AE `MWL_SCP`, port `11112`, bind `0.0.0.0`. Use different ports for simultaneous Storage/MWL SCP.
- Wrong Called AE: A-ASSOCIATE-RJ, service-user, reason 7. Optional Calling AE whitelist: exact case-sensitive comparison, RJ reason 3. IP rejection: RJ no-reason-given. No TLS/authentication is claimed.
- Per-association query tasks and C-CANCEL handlers scoped to Message ID. Concurrent associations have independent cancellation. A-RELEASE/A-ABORT use dcm4che transport handling; server stop closes listeners and active transport resources.

## Identifier, matching and returned attributes

SPS `(0040,0100)` is SQ with one Item when matching its attributes. A zero-length SQ/empty Item supports universal return-key requests. Multiple Items or a non-SQ SPS are rejected with A900.

| Key | Implemented matching |
|---|---|
| Patient Name | Case-insensitive PN components; `^` positions and `=` groups retained; wildcard `*`/`?`; no fuzzy/phonetic negotiation |
| Patient ID | Case-sensitive Single Value |
| Accession Number, Requested Procedure ID, SPS ID, Station Name | Case-sensitive single/wildcard matching |
| Study Instance UID | Single Value |
| SPS Modality, Scheduled Station AE | Case-sensitive Single Value |
| SPS Date/Time | Single/range; when both are ranges they form one continuous date-time interval; open boundaries supported |
| Scheduled Performing Physician Name | PN single/wildcard matching |

Unspecified PN components do not constrain those positions; supplied components cannot shift into other positions. Values are matched using the current patient record and a consistent repository snapshot. Root Modality is accepted as an explicitly documented legacy compatibility extension. DA/TM are local values; timezone adjustment is not negotiated.

Unsupported optional matching keys are ignored as Annex K permits. Unsupported optional return keys are returned empty with Pending Warning FF01. The SCP supports the patient fields, accession, Study UID, requested procedure identifiers/descriptions, referring/requesting physician, admission ID and SPS fields represented in `MwlEntities.kt`. Requested unknown keys retain their VR; no clinical values are invented. It does not provide a code-based procedure database.

Patient Name/ID, Study UID, Requested Procedure ID, SPS ID, station AE, modality, date/time and the supported procedure description alternatives must be valid before an entity is sent. Stored nonconforming legacy entities are retained for correction and fail transmission rather than being silently truncated. Generated test locations respect SH length.

| Status | Meaning |
|---|---|
| FF00 | Pending, known keys supported |
| FF01 | Pending warning, optional return key unsupported |
| 0000 | Matching complete |
| FE00 | C-CANCEL processed |
| A900 | Identifier inconsistent with MWL structure/range syntax |
| C000 | Unable to process, including invalid stored entity or lossless charset failure |

Final responses carry no Identifier. Existing in-flight Pending responses may arrive at an SCU before FE00; no new Pending is written after cancellation has been processed by the task gate.

## Character sets

Response choices: default ASCII (UI `ISO_IR 6`, omitted `(0008,0005)`), ISO_IR 100, ISO_IR 144, ISO_IR 192, ISO 2022 IR 100, ISO 2022 IR 144 and GB18030. UTF-8 is the default. The dataset and nested strings are serialized and decoded before sending to detect replacement characters/encoding loss. Nonrepresentable text produces Failure; automatic transliteration/fallback is not performed. Specific Character Set is inherited by nested Items.

SCU identifiers use UTF-8 with SPS filters nested correctly. Incoming character sets are decoded by dcm4che; an absent `(0008,0005)` means the default repertoire. Tests demonstrate representative Cyrillic, Latin-1 and ASCII cases, not every character/ISO 2022 combination.

## SCU outcomes and transport

Success, remote FE00 and local interruption without FE00 remain distinct. DICOM Failure returns an error containing its final status and any partial records. Missing Status/final response or an interrupted transport cannot confirm success. Unknown attributes and sequences are retained in raw Attributes and displayed recursively with their VR/Items; binary values show size. Display depth is limited to 32.

The profile configures local TCP connect, association accept, initial response, subsequent response/retrieve and release timeouts (1–120000 ms). Cancellation during negotiation closes the established TCP socket; cancellation of an active query sends actual C-CANCEL outside the UI thread, with a bounded wait for acknowledgement. OS DNS resolution has no separate application deadline. Executors, socket and association references are released in finally.

## Persistence, diagnostics and Android

Patients and appointments are committed as one fsynced atomic JSON snapshot. Previous two-file data is read and migrates at the next successful save. Profiles use atomic replacement too. Failed writes leave committed state and memory unchanged. Export/import version 1 preserves all supported fields, timestamps and patients without appointments; import is transactional and limited to 8 MiB/depth 64.

Full local diagnostic logs may contain PHI and stay in app-private storage. The anonymized report excludes free text and clinical identifiers; PHI inclusion requires the explicit export option. A single background writer has a 256-entry queue, 2 MiB active file plus one backup; queue drops/write errors are visible in the report. The journal is diagnostic, not a lossless medical archive.

MWL uses an unexported `connectedDevice` FGS for external devices over a network, with its required permissions; Storage retains its existing service configuration. CPU Wake Lock is renewed every minute with a two-minute expiry and released on shutdown. Wi-Fi HIGH_PERF is best effort on API 26–33; API 34+ remaps it to LOW_LATENCY and cannot guarantee background physical Wi-Fi. Network callbacks refresh IP information; the all-interface listener remains bound, but interrupted associations are not resumed/replayed automatically.

Android 15 emulator tests cover FGS startup, screen-off loopback, notification stop, Wake Lock release, port release and concurrent Storage independence. Physical Wi-Fi/Doze/OEM behavior, power loss, real modalities and the full character repertoire remain acceptance tests.
