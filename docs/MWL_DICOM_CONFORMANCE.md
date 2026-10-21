# DICOM Kit — MWL conformance notes, 1.5.0-alpha.4

This describes the implemented subset and measured behavior; it is not a claim of certification on all modalities. See [audit fixes and evidence](STAGE_7_AUDIT_FIXES.md).

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
