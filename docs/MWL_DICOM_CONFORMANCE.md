# DICOM Kit — MWL conformance notes, 1.5.0-alpha.2

This describes the implemented subset and measured behavior; it is not a claim of certification on all modalities. See [audit fixes and evidence](STAGE_7_AUDIT_FIXES.md).

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
