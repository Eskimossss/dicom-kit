# DICOM Conformance Statement: Modality Worklist SOP Class

**Application Name:** DICOM Kit  
**Release:** Stage 7 (v1.4.0-dev)  
**Standard:** DICOM PS3.2, PS3.4 Annex K, PS3.7, PS3.8  

---

## 1. SOP Classes Supported

DICOM Kit provides Standard Conformance to the following DICOM V3.0 SOP Classes:

| SOP Class Name | SOP Class UID | SCU Role | SCP Role |
|---|---|---|---|
| Modality Worklist Information Model - FIND | `1.2.840.10008.5.1.4.31` | Yes | Yes |
| Verification SOP Class | `1.2.840.10008.1.1` | Yes | Yes |

---

## 2. Transfer Syntaxes Supported

The application supports negotiation of the following transfer syntaxes for MWL FIND and Verification:

| Transfer Syntax Name | UID |
|---|---|
| Explicit VR Little Endian | `1.2.840.10008.1.2.1` |
| Implicit VR Little Endian | `1.2.840.10008.1.2` |

---

## 3. MWL SCP Specification (Server)

### 3.1. Association Policies
- **Application Entity Title:** Default `DICOMKIT_MWL` (configurable, 1-16 characters).
- **Default TCP Port:** `11112` (configurable).
- **Concurrent Associations:** Multi-threaded cached pool handler.
- **Calling AE Whitelist:** Optional validation of incoming Calling AE Titles. Rejection reason: `2 (calling-AE-title-not-recognized)`.

### 3.2. C-FIND DIMSE-C Service Operation
The MWL SCP supports incoming C-FIND requests matching on the following key attributes:

#### Matching Key Attributes Supported

| DICOM Tag | Description | VR | Matching Type Supported |
|---|---|---|---|
| `(0010,0010)` | Patient's Name | PN | Single Value, Wildcard (`*`, `?`), Universal |
| `(0010,0020)` | Patient ID | LO | Single Value, Universal |
| `(0008,0050)` | Accession Number | SH | Single Value, Wildcard, Universal |
| `(0040,0100)` | Scheduled Procedure Step Sequence | SQ | Sequence Matching |
| ` > (0008,0060)` | Modality | CS | Single Value, Universal |
| ` > (0040,0001)` | Scheduled Station AE Title | AE | Single Value, Universal |
| ` > (0040,0002)` | Scheduled Procedure Step Start Date | DA | Single Value, Range Matching (`date1-date2`, `-date2`, `date1-`), Universal |
| ` > (0040,0003)` | Scheduled Procedure Step Start Time | TM | Single Value, Range Matching, Universal |
| ` > (0040,0009)` | Scheduled Procedure Step ID | SH | Single Value, Universal |
| `(0008,0060)` | Modality (root level tolerance) | CS | Handled identically to SPS Modality for compatibility |

#### Return Key Attributes Supported
The SCP populates all requested return keys if known. If an unknown return key is requested by the SCU, the attribute is returned with zero length as per PS3.4 Annex K.

Key attributes returned in Dataset:
- `(0008,0005)` Specific Character Set
- `(0008,0050)` Accession Number
- `(0008,0090)` Referring Physician's Name
- `(0010,0010)` Patient's Name
- `(0010,0020)` Patient ID
- `(0010,0030)` Patient's Birth Date
- `(0010,0040)` Patient's Sex
- `(0010,1030)` Patient's Weight
- `(0010,2000)` Medical Alerts
- `(0020,000D)` Study Instance UID
- `(0032,1060)` Requested Procedure Description
- `(0038,0010)` Admission ID
- `(0040,1001)` Requested Procedure ID
- `(0040,0100)` Scheduled Procedure Step Sequence:
  - `(0008,0060)` Modality
  - `(0040,0001)` Scheduled Station AE Title
  - `(0040,0002)` Scheduled Procedure Step Start Date
  - `(0040,0003)` Scheduled Procedure Step Start Time
  - `(0040,0006)` Scheduled Performing Physician's Name
  - `(0040,0007)` Scheduled Procedure Step Description
  - `(0040,0009)` Scheduled Procedure Step ID
  - `(0040,0010)` Scheduled Station Name
  - `(0040,0011)` Scheduled Procedure Step Location
  - `(0040,0020)` Scheduled Procedure Step Status (`SCHEDULED`)

### 3.3. Status Codes Returned

| Service Status | Meaning | Status Code |
|---|---|---|
| Success | Matching is complete - No further matches will be sent | `0x0000` |
| Pending | Matches are continuing - Current match supplied | `0xFF00` |
| Cancel | Matching terminated due to C-CANCEL-FIND request | `0xFE00` |
| Failure | Out of Resources or Internal Error | `0xA700` |

---

## 4. MWL SCU Specification (Client)

### 4.1. Association Policies
- **Calling AE Title:** Default `DICOMKIT_SCU` (configurable).
- **Association Negotiation:** SCU proposes Presentation Context #1 (`ModalityWorklistInformationModelFind`) and #3 (`Verification`) with Explicit VR Little Endian and Implicit VR Little Endian.
- **Cancellation:** Supports issuing `C-CANCEL-FIND-RQ` during an active query stream.

### 4.2. C-FIND Request Generation
- Generates standard C-FIND-RQ Identifiers with nested `(0040,0100)` Scheduled Procedure Step Sequence.
- Populates query filters (Modality, Patient ID, Patient Name with wildcards, Accession Number, Date ranges).
- Includes all standard MWL return keys to ensure receiving rich clinical and technical attributes.

---

## 5. Support for Extended Character Sets

Supported Specific Character Sets (`0008,0005`):
- `ISO_IR 100` (Latin Alphabet No. 1)
- `ISO_IR 144` (Cyrillic Alphabet ISO 8859-5)
- `ISO_IR 192` (Unicode in UTF-8)
- `ISO 2022 IR 100`, `ISO 2022 IR 144`
- `GB18030` (Chinese)
- Default repertoire (ASCII)
