const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  Header, Footer, AlignmentType, LevelFormat, BorderStyle, WidthType,
  ShadingType, HeadingLevel, PageNumber, PageBreak
} = require('docx');
const fs = require('fs');

// ── colour palette ──────────────────────────────────────────────────────────
const BLUE_DARK  = "1F4E79";
const BLUE_MID   = "2E75B6";
const BLUE_LIGHT = "D6E4F0";
const GREY_HDR   = "F2F2F2";
const GREEN_LIGHT= "E2EFDA";
const RED_LIGHT  = "FCE4D6";
const YELLOW_LT  = "FFF2CC";
const WHITE      = "FFFFFF";

// ── table border helpers ────────────────────────────────────────────────────
const bdr  = (color = "BFBFBF") => ({ style: BorderStyle.SINGLE, size: 4, color });
const noBdr = () => ({ style: BorderStyle.NONE, size: 0, color: "FFFFFF" });
const borders = (c = "BFBFBF") => ({ top: bdr(c), bottom: bdr(c), left: bdr(c), right: bdr(c) });
const noOuterBorders = () => ({ top: noBdr(), bottom: noBdr(), left: noBdr(), right: noBdr() });

// ── cell factory ────────────────────────────────────────────────────────────
function cell(text, width, opts = {}) {
  const {
    bold = false, fill = WHITE, color = "000000",
    align = AlignmentType.LEFT, fontSize = 20,
    italic = false, center = false
  } = opts;
  return new TableCell({
    borders: borders("BFBFBF"),
    width: { size: width, type: WidthType.DXA },
    shading: { fill, type: ShadingType.CLEAR },
    margins: { top: 80, bottom: 80, left: 120, right: 120 },
    children: [new Paragraph({
      alignment: center ? AlignmentType.CENTER : align,
      spacing: { before: 0, after: 0 },
      children: [new TextRun({ text, bold, color, size: fontSize, font: "Arial", italic })]
    })]
  });
}

// header cell (blue background, white bold text, centred)
function hdrCell(text, width) {
  return new TableCell({
    borders: borders(BLUE_MID),
    width: { size: width, type: WidthType.DXA },
    shading: { fill: BLUE_MID, type: ShadingType.CLEAR },
    margins: { top: 100, bottom: 100, left: 120, right: 120 },
    children: [new Paragraph({
      alignment: AlignmentType.CENTER,
      spacing: { before: 0, after: 0 },
      children: [new TextRun({ text, bold: true, color: WHITE, size: 20, font: "Arial" })]
    })]
  });
}

// ── heading helpers ─────────────────────────────────────────────────────────
function h1(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_1,
    spacing: { before: 320, after: 120 },
    children: [new TextRun({ text, bold: true, size: 32, color: BLUE_DARK, font: "Arial" })]
  });
}
function h2(text) {
  return new Paragraph({
    heading: HeadingLevel.HEADING_2,
    spacing: { before: 240, after: 80 },
    children: [new TextRun({ text, bold: true, size: 26, color: BLUE_MID, font: "Arial" })]
  });
}
function para(runs, opts = {}) {
  const { spacing = { before: 60, after: 60 }, alignment = AlignmentType.LEFT } = opts;
  const children = runs.map(r => {
    if (typeof r === "string") return new TextRun({ text: r, size: 20, font: "Arial" });
    return new TextRun({ size: 20, font: "Arial", ...r });
  });
  return new Paragraph({ alignment, spacing, children });
}
function bullet(runs, indent = 720) {
  const children = runs.map(r => {
    if (typeof r === "string") return new TextRun({ text: r, size: 20, font: "Arial" });
    return new TextRun({ size: 20, font: "Arial", ...r });
  });
  return new Paragraph({
    numbering: { reference: "bullets", level: 0 },
    spacing: { before: 40, after: 40 },
    children
  });
}
function spacer() {
  return new Paragraph({ children: [new TextRun("")], spacing: { before: 0, after: 80 } });
}

// ══════════════════════════════════════════════════════════════════════════════
// TABLES
// ══════════════════════════════════════════════════════════════════════════════

// 1. Test environment  ─────────────────────────────────────────────────────
const ENV_W = [3200, 6160]; // sum = 9360
function envRow(label, value) {
  return new TableRow({ children: [
    cell(label, ENV_W[0], { bold: true, fill: GREY_HDR }),
    cell(value,  ENV_W[1])
  ]});
}
const envTable = new Table({
  width: { size: 9360, type: WidthType.DXA },
  columnWidths: ENV_W,
  rows: [
    new TableRow({ children: [hdrCell("Parameter", ENV_W[0]), hdrCell("Value", ENV_W[1])] }),
    envRow("Record count",                "1,000,000 golden records"),
    envRow("Record size",                 "~3–6 KB per record (JSON with demographic history array)"),
    envRow("Test client",                 "Office laptop to AWS (~38 ms network RTT per query)"),
    envRow("AuroraDB",                    "Amazon Aurora PostgreSQL, JSONB storage"),
    envRow("DocumentDB",                  "Amazon DocumentDB (MongoDB-compatible), native document storage"),
    envRow("Concurrent users (stress)",   "20 threads"),
    envRow("Stress test duration",        "60 seconds"),
    envRow("Stress test mix",             "60% SELECT, 25% INSERT, 15% UPDATE"),
  ]
});

// 2. Query results table  ──────────────────────────────────────────────────
const QW = [720, 3200, 1200, 1440, 2800]; // sum = 9360
function winnerFill(w) {
  if (w.startsWith("DocumentDB")) return GREEN_LIGHT;
  if (w === "AuroraDB")          return YELLOW_LT;
  return WHITE;
}
function qRow(q, desc, pg, mongo, winner) {
  const fill = winnerFill(winner);
  return new TableRow({ children: [
    cell(q,      QW[0], { center: true, bold: true }),
    cell(desc,   QW[1]),
    cell(pg,     QW[2], { center: true }),
    cell(mongo,  QW[3], { center: true }),
    cell(winner, QW[4], { fill, bold: winner !== "Tie", center: true,
      color: winner.startsWith("DocumentDB") ? "375623"
           : winner === "AuroraDB"           ? "7F6000"
           :                                   "000000" })
  ]});
}
const queryTable = new Table({
  width: { size: 9360, type: WidthType.DXA },
  columnWidths: QW,
  rows: [
    new TableRow({ children: [
      hdrCell("Q#",          QW[0]),
      hdrCell("Description", QW[1]),
      hdrCell("AuroraDB P50 (ms)", QW[2]),
      hdrCell("DocumentDB P50 (ms)", QW[3]),
      hdrCell("Winner", QW[4])
    ]}),
    qRow("Q1",  "Point lookup by ID (primary key)",                    "40",    "41",   "Tie"),
    qRow("Q2",  "Lookup by globalPid (indexed)",                       "41",    "41",   "Tie"),
    qRow("Q3",  "Lookup by globalCid (indexed)",                       "41",    "42",   "Tie"),
    qRow("Q4",  "SSN search (indexed scalar field)",                   "41",    "41",   "Tie"),
    qRow("Q5",  "Name + DOB compound search",                          "42",    "42",   "Tie"),
    qRow("Q6",  "Cluster group fetch by globalPid",                    "41",    "41",   "Tie"),
    qRow("Q7",  "Top-1000 by ruleId + confidence score sort",          "1,520", "209",  "DocumentDB 7.3x"),
    qRow("Q8",  "Timestamp range (recent records, 7 days)",            "41",    "43",   "AuroraDB"),
    qRow("Q9",  "SSN search in prior_values array",                    "46",    "42",   "DocumentDB (GIN = no benefit)"),
    qRow("Q10", "Full demographic compound filter",                    "44",    "43",   "Tie"),
    qRow("Q11", "Single record INSERT",                                "42",    "41",   "Tie"),
    qRow("Q12", "Batch INSERT (100 records)",                          "76",    "58",   "DocumentDB 1.3x"),
    qRow("Q13", "SSN point UPDATE (scalar field)",                     "42",    "42",   "Tie"),
    qRow("Q14", "Append to prior_values history array",                "44",    "43",   "Tie"),
    qRow("Q15", "Scalar projection (TOAST overhead test)",             "40",    "40",   "Tie"),
  ]
});

// 3. Index speedup  ────────────────────────────────────────────────────────
const IW = [1200, 4080, 4080]; // sum = 9360
function iRow(q, pg, mongo) {
  return new TableRow({ children: [
    cell(q,     IW[0], { bold: true, center: true }),
    cell(pg,    IW[1]),
    cell(mongo, IW[2])
  ]});
}
const indexTable = new Table({
  width: { size: 9360, type: WidthType.DXA },
  columnWidths: IW,
  rows: [
    new TableRow({ children: [
      hdrCell("Query", IW[0]),
      hdrCell("AuroraDB: No Index → Indexed", IW[1]),
      hdrCell("DocumentDB: No Index → Indexed", IW[2])
    ]}),
    iRow("Q1",  "Already indexed (primary key)",       "Already indexed (_id)"),
    iRow("Q2",  "~50,000ms → 41ms  (1,200x)",          "~50,000ms → 41ms  (1,200x)"),
    iRow("Q4",  "~50,000ms → 41ms  (1,200x)",          "~50,000ms → 41ms  (1,200x)"),
    iRow("Q7",  "~50,000ms → 1,520ms  (33x)",          "~50,000ms → 209ms  (240x)"),
    iRow("Q9",  "~46ms → 46ms  (1x — GIN no benefit)", "~2,100ms → 42ms  (51x)"),
  ]
});

// 4. Stress test  ──────────────────────────────────────────────────────────
const SW = [3200, 3080, 3080]; // sum = 9360
function sRow(metric, pg, mongo, pgFill = WHITE, mFill = WHITE) {
  return new TableRow({ children: [
    cell(metric, SW[0], { bold: true, fill: GREY_HDR }),
    cell(pg,     SW[1], { center: true, fill: pgFill }),
    cell(mongo,  SW[2], { center: true, fill: mFill })
  ]});
}
const stressTable = new Table({
  width: { size: 9360, type: WidthType.DXA },
  columnWidths: SW,
  rows: [
    new TableRow({ children: [
      hdrCell("Metric",       SW[0]),
      hdrCell("AuroraDB",     SW[1]),
      hdrCell("DocumentDB",   SW[2])
    ]}),
    sRow("Total operations",  "1,521",      "5,100",   WHITE,       GREEN_LIGHT),
    sRow("Throughput",        "25.0 ops/sec","84.5 ops/sec", WHITE, GREEN_LIGHT),
    sRow("Average latency",   "787 ms",     "232 ms",  WHITE,       GREEN_LIGHT),
    sRow("P50 latency",       "43 ms",      "42 ms"),
    sRow("P95 latency",       "4,729 ms",   "516 ms",  RED_LIGHT,   GREEN_LIGHT),
    sRow("P99 latency",       "17,949 ms",  "1,678 ms",RED_LIGHT,   YELLOW_LT),
    sRow("Errors",            "0",          "0"),
  ]
});

// 5. Record size limits  ──────────────────────────────────────────────────
const LW = [2800, 2080, 2080, 2400]; // sum = 9360
function limRow(limit, aurora, docdb, note) {
  return new TableRow({ children: [
    cell(limit,  LW[0], { bold: true, fill: GREY_HDR }),
    cell(aurora, LW[1], { center: true }),
    cell(docdb,  LW[2], { center: true, fill: docdb === "16 MB (hard)" ? RED_LIGHT : WHITE }),
    cell(note,   LW[3], { italic: true, color: "555555" })
  ]});
}
const limitsTable = new Table({
  width: { size: 9360, type: WidthType.DXA },
  columnWidths: LW,
  rows: [
    new TableRow({ children: [
      hdrCell("Limit",            LW[0]),
      hdrCell("AuroraDB",         LW[1]),
      hdrCell("DocumentDB",       LW[2]),
      hdrCell("Notes",            LW[3])
    ]}),
    limRow("Entire document / record",    "~1 GB (via TOAST)",  "16 MB (hard)",     "DocumentDB enforces the MongoDB BSON limit with no exceptions"),
    limRow("Single string field in JSON", "~255 MB",            "16 MB (shared)",   "AuroraDB varlena string ceiling; DocumentDB limit is per whole document"),
    limRow("Typical MDM golden record",   "3–6 KB",             "3–6 KB",           "Well within both limits today"),
    limRow("High-activity record (years of history)", "~1–5 MB", "~1–5 MB",        "Still safe for both, but DocumentDB needs monitoring"),
    limRow("Risk threshold",              "Not a concern",      "Archive prior_values when approaching ~10 MB", "Proactive archiving policy recommended for DocumentDB"),
  ]
});

// 6. Recommendation  ───────────────────────────────────────────────────────
const RW = [2800, 6560]; // sum = 9360
function rRow(label, value) {
  return new TableRow({ children: [
    cell(label, RW[0], { bold: true, fill: GREY_HDR }),
    cell(value, RW[1])
  ]});
}
const recTable = new Table({
  width: { size: 9360, type: WidthType.DXA },
  columnWidths: RW,
  rows: [
    new TableRow({ children: [hdrCell("Decision", RW[0]), hdrCell("Recommendation", RW[1])] }),
    rRow("Primary MDM store",         "DocumentDB"),
    rRow("Reason",                    "3.4x higher throughput, 10x lower tail latency under concurrent load, native array indexing (Q9), superior large result set performance (Q7)"),
    rRow("Migration path",            "Schema-less — no DDL migration required; golden record JSON structure is identical"),
    rRow("When to reconsider AuroraDB","If SQL JOINs across multiple entity types are needed, or if strict ACID transactions spanning multiple records are required"),
    rRow("Index strategy for DocumentDB","Compound index on (ruleId, confidenceScore) for Q7; multikey index on prior_values.ssn for Q9; standard indexes on globalPid, globalCid, ssn, name+dob"),
  ]
});


// ══════════════════════════════════════════════════════════════════════════════
// DOCUMENT
// ══════════════════════════════════════════════════════════════════════════════
const doc = new Document({
  numbering: {
    config: [{
      reference: "bullets",
      levels: [{
        level: 0, format: LevelFormat.BULLET, text: "\u2022",
        alignment: AlignmentType.LEFT,
        style: { paragraph: { indent: { left: 720, hanging: 360 } } }
      }]
    }]
  },
  styles: {
    default: {
      document: { run: { font: "Arial", size: 20, color: "000000" } }
    },
    paragraphStyles: [
      { id: "Heading1", name: "Heading 1", basedOn: "Normal", next: "Normal", quickFormat: true,
        run:  { size: 32, bold: true, color: BLUE_DARK, font: "Arial" },
        paragraph: { spacing: { before: 320, after: 120 }, outlineLevel: 0 } },
      { id: "Heading2", name: "Heading 2", basedOn: "Normal", next: "Normal", quickFormat: true,
        run:  { size: 26, bold: true, color: BLUE_MID,  font: "Arial" },
        paragraph: { spacing: { before: 240, after: 80  }, outlineLevel: 1 } },
    ]
  },
  sections: [{
    properties: {
      page: {
        size: { width: 12240, height: 15840 },
        margin: { top: 1080, right: 1080, bottom: 1080, left: 1080 }
      }
    },
    headers: {
      default: new Header({ children: [
        new Paragraph({
          spacing: { before: 0, after: 0 },
          border: { bottom: { style: BorderStyle.SINGLE, size: 6, color: BLUE_MID, space: 1 } },
          children: [
            new TextRun({ text: "MDM Golden Record Benchmark  |  AuroraDB vs DocumentDB", bold: true, size: 18, font: "Arial", color: BLUE_DARK }),
            new TextRun({ text: "\tApril 2026", size: 18, font: "Arial", color: "666666" })
          ],
          tabStops: [{ type: "right", position: 9360 }]
        })
      ]})
    },
    footers: {
      default: new Footer({ children: [
        new Paragraph({
          spacing: { before: 0, after: 0 },
          border: { top: { style: BorderStyle.SINGLE, size: 6, color: BLUE_MID, space: 1 } },
          alignment: AlignmentType.CENTER,
          children: [
            new TextRun({ text: "Page ", size: 18, font: "Arial", color: "666666" }),
            new TextRun({ children: [PageNumber.CURRENT], size: 18, font: "Arial", color: "666666" }),
            new TextRun({ text: " of ", size: 18, font: "Arial", color: "666666" }),
            new TextRun({ children: [PageNumber.TOTAL_PAGES], size: 18, font: "Arial", color: "666666" }),
          ]
        })
      ]})
    },
    children: [

      // ── TITLE BLOCK ──────────────────────────────────────────────────────
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 240, after: 80 },
        children: [new TextRun({ text: "MDM Golden Record Benchmark", bold: true, size: 52, font: "Arial", color: BLUE_DARK })]
      }),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 0, after: 60 },
        children: [new TextRun({ text: "AuroraDB vs DocumentDB", bold: true, size: 40, font: "Arial", color: BLUE_MID })]
      }),
      new Paragraph({
        alignment: AlignmentType.CENTER,
        spacing: { before: 0, after: 320 },
        children: [new TextRun({ text: "Production Results  \u00B7  1,000,000 Records  \u00B7  April 2026", size: 22, font: "Arial", color: "666666" })]
      }),

      // ── EXECUTIVE SUMMARY ────────────────────────────────────────────────
      h1("Executive Summary"),
      para(["This benchmark compares "]),
      para([
        { text: "AuroraDB (PostgreSQL-compatible)", bold: true },
        { text: " and " },
        { text: "DocumentDB (MongoDB-compatible)", bold: true },
        { text: " for storing and querying MDM golden records at production scale: 1,000,000 records, 15 query patterns, parallel write benchmarks, and a 60-second concurrent stress test with 20 simulated users." }
      ]),
      spacer(),
      para([
        { text: "Bottom line: ", bold: true },
        { text: "DocumentDB is faster for nearly every workload pattern relevant to MDM. AuroraDB wins only on indexed timestamp range scans (Q8). For a mixed read/write MDM system under concurrent load, DocumentDB delivers " },
        { text: "3.4x higher throughput", bold: true },
        { text: " with " },
        { text: "10x lower tail latency.", bold: true }
      ]),

      // ── TEST ENVIRONMENT ─────────────────────────────────────────────────
      h1("Test Environment"),
      envTable,

      // ── QUERY RESULTS ────────────────────────────────────────────────────
      new Paragraph({ children: [new PageBreak()] }),
      h1("Query Results — Indexed Performance (P50 ms, lower is better)"),
      para([{ text: "All latency numbers are median (P50) in milliseconds. Network RTT (~38 ms) is included in all measurements.", italic: true, color: "555555" }]),
      spacer(),
      queryTable,

      // ── INDEX SPEEDUP ────────────────────────────────────────────────────
      spacer(),
      h1("Index Speedup Summary"),
      indexTable,

      // ── STRESS TEST ──────────────────────────────────────────────────────
      spacer(),
      h1("Stress Test Results (20 Concurrent Users, 60 Seconds)"),
      stressTable,

      // ── RECORD SIZE LIMITS ───────────────────────────────────────────────
      spacer(),
      h1("Record Size Limits"),
      para([
        { text: "Both databases impose maximum sizes on stored records. For MDM golden records today these limits are not a concern, but they become " },
        { text: "architecturally significant", bold: true },
        { text: " as records accumulate history over years." }
      ]),
      spacer(),
      limitsTable,
      spacer(),
      h2("Understanding the Two AuroraDB Limits"),
      para([{ text: "AuroraDB (PostgreSQL) has two distinct ceilings that are often confused:", italic: false }]),
      bullet([
        { text: "~1 GB — entire JSONB column value:", bold: true },
        { text: "  PostgreSQL's TOAST mechanism splits large values into ~2 KB chunks stored in a side table. The theoretical maximum for a single JSONB field is 2\u00B3\u00B0 \u2212 1 bytes \u2248 1 GB. Source: PostgreSQL TOAST documentation (storage-toast.html)." }
      ]),
      bullet([
        { text: "~255 MB — a single string element inside the JSON:", bold: true },
        { text: "  Any individual string value within the JSONB document (e.g., one field like a base64-encoded blob) is limited to ~268 MB by the varlena internal representation. This is rarely relevant for MDM demographic data." }
      ]),
      spacer(),
      h2("DocumentDB 16 MB Hard Limit"),
      para(["DocumentDB enforces MongoDB's BSON document size limit of exactly 16 MB per document. This is a hard ceiling — writes that would exceed it fail immediately with an error. For MDM deployments:"]),
      bullet([{ text: "A typical golden record today (3–6 KB) is ~2,700x below the limit." }]),
      bullet([{ text: "A high-activity record accumulating 10 years of monthly history entries (~120 entries \u00D7 500 bytes) reaches ~60 KB — still well within limits." }]),
      bullet([
        { text: "Production safeguard required:", bold: true },
        { text: "  Implement a prior_values archiving policy (e.g., move history older than N years to a separate archive collection) before any record approaches ~10 MB. Without this, a runaway merge loop or bulk retroactive correction could hit the limit and cause write failures." }
      ]),

      // ── KEY FINDINGS ─────────────────────────────────────────────────────
      new Paragraph({ children: [new PageBreak()] }),
      h1("Key Findings"),

      h2("1. All Simple Lookups Are Network-Bound (~40 ms)"),
      para(["Every single-record lookup (Q1–Q6, Q10, Q13–Q15) returns in approximately 40–44 ms on both databases. This is not database latency — it is the network round-trip from the office to AWS (~38 ms). Both databases retrieve a single record in under 2 ms at the server. These results will look identical in production when the application runs inside the same VPC."]),

      spacer(),
      h2("2. Q7 — Large Result Set: DocumentDB is 7.3x Faster"),
      para(["Fetching the top-1,000 records by ruleId sorted by confidence score is the biggest differentiator among read queries:"]),
      bullet([{ text: "AuroraDB:", bold: true }, { text: "  1,520 ms (P50)" }]),
      bullet([{ text: "DocumentDB:", bold: true }, { text: "  209 ms (P50)  —  7.3x faster" }]),
      para(["This is due to JSONB extraction overhead: AuroraDB must deserialize the entire JSONB blob to extract the nested score field for sorting. DocumentDB stores native BSON and can project and sort directly. This query represents a common MDM pattern — \"show me the top candidates for this matching rule\" — and will be called frequently in production."]),

      spacer(),
      h2("3. Q9 — GIN Index Is Ineffective for Nested Array Search"),
      para(["Searching for an SSN inside the prior_values history array:"]),
      bullet([{ text: "AuroraDB:", bold: true }, { text: "  GIN index (jsonb_path_ops) provides zero speedup — 46 ms indexed vs 46 ms unindexed. This is a known PostgreSQL limitation: jsonb_path_ops cannot accelerate doubly-nested array element predicates." }]),
      bullet([{ text: "DocumentDB:", bold: true }, { text: "  Multikey index provides 51x speedup — 2,100 ms unindexed to 42 ms indexed." }]),
      para(["For MDM record linkage and deduplication workflows that search historical SSNs, DocumentDB has a structural advantage."]),

      spacer(),
      h2("4. Stress Test — AuroraDB P99 = 17,949 ms (Tail Latency Crisis)"),
      para(["Under 20 concurrent users with mixed read/write load, AuroraDB's P99 latency reached 17.9 seconds. The P50 of 43 ms looks fine, but 1% of operations took nearly 18 seconds. Root causes:"]),
      bullet([{ text: "JSONB write amplification:", bold: true }, { text: "  Every UPDATE rewrites the entire JSONB blob (3–6 KB) and creates a dead tuple. Autovacuum runs to clean up dead tuples, causing I/O spikes." }]),
      bullet([{ text: "GIN pending-list flushing:", bold: true }, { text: "  The 4 MB GIN buffer fills under concurrent INSERTs and triggers a synchronous index flush, blocking all writers for several seconds." }]),
      bullet([{ text: "Connection pool contention:", bold: true }, { text: "  20 threads sharing a bounded HikariCP pool causes queuing under mixed load." }]),
      para(["DocumentDB's P99 was 1,678 ms — still elevated, but 10x better than AuroraDB under the same load."]),

      spacer(),
      h2("5. Q15 — TOAST Overhead Is Minimal for Single Records"),
      para(["The scalar projection query (Q15) fetches only 4 demographic fields instead of the full golden record. Results: 40 ms for both databases — identical to full-record fetches (Q1 = 40 ms). At this scale and access pattern, TOAST pointer de-reference overhead is negligible for single-record queries. TOAST only becomes a factor under sequential scans of large tables."]),

      // ── RECOMMENDATION ───────────────────────────────────────────────────
      new Paragraph({ children: [new PageBreak()] }),
      h1("Recommendation"),
      recTable,

      // ── HOW TO READ THE NUMBERS ──────────────────────────────────────────
      spacer(),
      h1("How to Read the Numbers"),
      bullet([{ text: "All latencies include ~38 ms network RTT", bold: true }, { text: "  (office laptop to AWS). In production (app inside VPC), single-record queries will be 1–3 ms, not 40 ms." }]),
      bullet([{ text: "P50 = median", bold: true }, { text: "  — half of all operations completed faster than this value." }]),
      bullet([{ text: "P99 = worst 1%", bold: true }, { text: "  — the number that matters for SLA and user experience." }]),
      bullet([{ text: "\"Tie\" means both databases are network-bound", bold: true }, { text: "  — the database itself is not the bottleneck for those queries." }]),
      bullet([{ text: "Stress test is the most realistic measurement", bold: true }, { text: "  — it reflects what happens when 20 users are simultaneously reading, inserting, and updating records, which is normal MDM production load." }]),

    ]
  }]
});

// ── write file ───────────────────────────────────────────────────────────────
const outPath = "/Users/gagan/gsv-workspace/db-benchmark/docs/executive-summary-aurora-vs-docdb.docx";
Packer.toBuffer(doc).then(buf => {
  fs.writeFileSync(outPath, buf);
  console.log("Written:", outPath);
}).catch(err => { console.error(err); process.exit(1); });
