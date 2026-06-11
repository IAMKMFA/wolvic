// Off-thread point-cloud parser for the Framatome cloud viewer.
//
// Parsing a multi-million-point scan on the main thread freezes the XR render
// loop for seconds (the loading panel can't even spin) — so the common formats
// are parsed HERE, in a worker, and only the finished typed arrays cross back
// (as transferables, zero-copy). This parser is deliberately self-contained:
// module workers can't see the page's import map, so it does not import three.
//
// Coverage (everything SCAN_PIPELINE.md tells techs to deliver):
//   PLY  — binary_little_endian / binary_big_endian / ascii, scalar vertex
//          properties, red/green/blue (or diffuse_*) colors in uchar/ushort/
//          float. Vertex elements with LIST properties fall back.
//   PCD  — ascii / binary, x/y/z + packed rgb/rgba field. binary_compressed
//          (LZF) falls back.
// Anything unhandled posts { ok:false, unsupported } WITH the buffer returned
// (transferred back), so the caller can run the vendored three.js loaders
// synchronously as ground truth.
//
// Invalid points: organized PCDs and some scan exports encode dropped returns
// as NaN positions by spec. One NaN poisons THREE's bounding box and blanks
// the whole cloud, so non-finite triples are filtered out here (the message
// reports total vs kept so the viewer can surface the drop).

"use strict";

/* ----------------------------------------------------------------- utils -- */

function asciiLine(bytes, from) {
  // Returns [line, nextOffset] for the \n-terminated line starting at `from`.
  let end = from;
  while (end < bytes.length && bytes[end] !== 10) end++;
  let line = "";
  for (let i = from; i < end; i++) line += String.fromCharCode(bytes[i]);
  return [line.replace(/\r$/, ""), end + 1];
}

// Builds compacted (finite-only) position/color arrays.
// readPoint(i, out) must fill out[0..2] = xyz and, when colors exist,
// out[3..5] = rgb in 0..1.
function compactPoints(count, hasColor, readPoint) {
  const positions = new Float32Array(count * 3);
  const colors = hasColor ? new Float32Array(count * 3) : null;
  const p = [0, 0, 0, 0, 0, 0];
  let kept = 0;
  for (let i = 0; i < count; i++) {
    readPoint(i, p);
    if (!Number.isFinite(p[0]) || !Number.isFinite(p[1]) || !Number.isFinite(p[2])) continue;
    const j = kept * 3;
    positions[j] = p[0]; positions[j + 1] = p[1]; positions[j + 2] = p[2];
    if (colors) { colors[j] = p[3]; colors[j + 1] = p[4]; colors[j + 2] = p[5]; }
    kept++;
  }
  return {
    positions: kept === count ? positions : positions.slice(0, kept * 3),
    colors: colors ? (kept === count ? colors : colors.slice(0, kept * 3)) : null,
    total: count,
    kept,
  };
}

/* ------------------------------------------------------------------- PLY -- */

const PLY_SIZES = {
  char: 1, int8: 1, uchar: 1, uint8: 1,
  short: 2, int16: 2, ushort: 2, uint16: 2,
  int: 4, int32: 4, uint: 4, uint32: 4, float: 4, float32: 4,
  double: 8, float64: 8,
};

function plyScalarReader(type, littleEndian) {
  switch (type) {
    case "char": case "int8": return (dv, o) => dv.getInt8(o);
    case "uchar": case "uint8": return (dv, o) => dv.getUint8(o);
    case "short": case "int16": return (dv, o) => dv.getInt16(o, littleEndian);
    case "ushort": case "uint16": return (dv, o) => dv.getUint16(o, littleEndian);
    case "int": case "int32": return (dv, o) => dv.getInt32(o, littleEndian);
    case "uint": case "uint32": return (dv, o) => dv.getUint32(o, littleEndian);
    case "float": case "float32": return (dv, o) => dv.getFloat32(o, littleEndian);
    case "double": case "float64": return (dv, o) => dv.getFloat64(o, littleEndian);
    default: return null;
  }
}

function plyColorScale(type) {
  if (type === "uchar" || type === "uint8") return 1 / 255;
  if (type === "ushort" || type === "uint16") return 1 / 65535;
  return 1; // float colors are already 0..1
}

function parsePly(buffer) {
  const bytes = new Uint8Array(buffer);
  // Header is ASCII lines up to and including "end_header".
  let off = 0;
  let [magic, next] = asciiLine(bytes, off);
  if (magic.trim() !== "ply") throw new Error("not a PLY file");
  off = next;

  let format = "";
  const elements = []; // { name, count, props: [{name, type, isList}] }
  let current = null;
  for (;;) {
    if (off >= bytes.length) throw new Error("PLY header is truncated");
    const [line, n] = asciiLine(bytes, off);
    off = n;
    const t = line.trim().split(/\s+/);
    if (!t[0] || t[0] === "comment" || t[0] === "obj_info") continue;
    if (t[0] === "format") { format = t[1]; continue; }
    if (t[0] === "element") {
      current = { name: t[1], count: parseInt(t[2], 10) || 0, props: [] };
      elements.push(current);
      continue;
    }
    if (t[0] === "property" && current) {
      if (t[1] === "list") current.props.push({ name: t[t.length - 1], type: null, isList: true });
      else current.props.push({ name: t[2], type: t[1], isList: false });
      continue;
    }
    if (t[0] === "end_header") break;
  }
  if (!elements.length || elements[0].name !== "vertex") {
    return { unsupported: "PLY vertex element is not first" };
  }
  const vertex = elements[0];
  if (vertex.props.some((p) => p.isList)) {
    return { unsupported: "PLY list property in vertex element" };
  }

  const idx = {};
  vertex.props.forEach((p, i) => { idx[p.name] = i; });
  const xi = idx.x, yi = idx.y, zi = idx.z;
  if (xi === undefined || yi === undefined || zi === undefined) {
    throw new Error("PLY has no x/y/z vertex positions");
  }
  const ri = idx.red !== undefined ? idx.red : idx.diffuse_red;
  const gi = idx.green !== undefined ? idx.green : idx.diffuse_green;
  const bi = idx.blue !== undefined ? idx.blue : idx.diffuse_blue;
  const hasColor = ri !== undefined && gi !== undefined && bi !== undefined;
  const colorScale = hasColor ? plyColorScale(vertex.props[ri].type) : 1;

  if (format === "ascii") {
    const text = new TextDecoder().decode(bytes.subarray(off));
    const tokens = text.trim().split(/\s+/);
    const stride = vertex.props.length;
    if (tokens.length < vertex.count * stride) throw new Error("PLY vertex data is truncated");
    return compactPoints(vertex.count, hasColor, (i, p) => {
      const base = i * stride;
      p[0] = parseFloat(tokens[base + xi]);
      p[1] = parseFloat(tokens[base + yi]);
      p[2] = parseFloat(tokens[base + zi]);
      if (hasColor) {
        p[3] = parseFloat(tokens[base + ri]) * colorScale;
        p[4] = parseFloat(tokens[base + gi]) * colorScale;
        p[5] = parseFloat(tokens[base + bi]) * colorScale;
      }
    });
  }

  if (format !== "binary_little_endian" && format !== "binary_big_endian") {
    return { unsupported: "PLY format " + format };
  }
  const le = format === "binary_little_endian";
  const readers = [];
  const offsets = [];
  let stride = 0;
  for (const p of vertex.props) {
    const size = PLY_SIZES[p.type];
    const reader = plyScalarReader(p.type, le);
    if (!size || !reader) return { unsupported: "PLY property type " + p.type };
    offsets.push(stride);
    readers.push(reader);
    stride += size;
  }
  if (off + vertex.count * stride > bytes.length) throw new Error("PLY vertex data is truncated");
  const dv = new DataView(buffer, off);
  return compactPoints(vertex.count, hasColor, (i, p) => {
    const base = i * stride;
    p[0] = readers[xi](dv, base + offsets[xi]);
    p[1] = readers[yi](dv, base + offsets[yi]);
    p[2] = readers[zi](dv, base + offsets[zi]);
    if (hasColor) {
      p[3] = readers[ri](dv, base + offsets[ri]) * colorScale;
      p[4] = readers[gi](dv, base + offsets[gi]) * colorScale;
      p[5] = readers[bi](dv, base + offsets[bi]) * colorScale;
    }
  });
}

/* ------------------------------------------------------------------- PCD -- */

function parsePcd(buffer) {
  const bytes = new Uint8Array(buffer);
  const header = { fields: [], size: [], type: [], count: [], points: 0, width: 0, height: 1, data: "" };
  let off = 0;
  // Header is ASCII lines up to and including the DATA line.
  for (;;) {
    if (off >= bytes.length) throw new Error("PCD header is truncated");
    const [line, n] = asciiLine(bytes, off);
    off = n;
    const t = line.trim().split(/\s+/);
    const key = (t[0] || "").toUpperCase();
    if (!key || key.startsWith("#")) continue;
    if (key === "FIELDS") header.fields = t.slice(1).map((f) => f.toLowerCase());
    else if (key === "SIZE") header.size = t.slice(1).map(Number);
    else if (key === "TYPE") header.type = t.slice(1).map((s) => s.toUpperCase());
    else if (key === "COUNT") header.count = t.slice(1).map(Number);
    else if (key === "POINTS") header.points = parseInt(t[1], 10) || 0;
    else if (key === "WIDTH") header.width = parseInt(t[1], 10) || 0;
    else if (key === "HEIGHT") header.height = parseInt(t[1], 10) || 1;
    else if (key === "DATA") { header.data = (t[1] || "").toLowerCase(); break; }
  }
  // POINTS is mandatory since PCD 0.7 but tolerate organized files without it.
  if (!header.points) header.points = header.width * Math.max(1, header.height);
  if (!header.count.length) header.count = header.fields.map(() => 1);
  const xi = header.fields.indexOf("x");
  const yi = header.fields.indexOf("y");
  const zi = header.fields.indexOf("z");
  if (xi < 0 || yi < 0 || zi < 0) throw new Error("PCD has no x/y/z fields");
  let ci = header.fields.indexOf("rgb");
  if (ci < 0) ci = header.fields.indexOf("rgba");
  const hasColor = ci >= 0;

  if (header.data === "ascii") {
    const text = new TextDecoder().decode(bytes.subarray(off));
    const tokens = text.trim().split(/\s+/);
    // Token index of each field start (COUNT-aware).
    const starts = [];
    let acc = 0;
    for (let i = 0; i < header.fields.length; i++) { starts.push(acc); acc += header.count[i] || 1; }
    const stride = acc;
    if (tokens.length < header.points * stride) throw new Error("PCD data is truncated");
    return compactPoints(header.points, hasColor, (i, p) => {
      const base = i * stride;
      p[0] = parseFloat(tokens[base + starts[xi]]);
      p[1] = parseFloat(tokens[base + starts[yi]]);
      p[2] = parseFloat(tokens[base + starts[zi]]);
      if (hasColor) {
        // ascii rgb is the packed value printed as a (float or uint) number.
        let v = parseFloat(tokens[base + starts[ci]]);
        if (header.type[ci] === "F") {
          _f32[0] = v; v = _u32[0]; // reinterpret float bits as packed uint
        }
        v = v >>> 0;
        p[3] = ((v >> 16) & 255) / 255;
        p[4] = ((v >> 8) & 255) / 255;
        p[5] = (v & 255) / 255;
      }
    });
  }

  if (header.data !== "binary") {
    return { unsupported: "PCD data encoding " + header.data }; // binary_compressed -> three PCDLoader
  }
  const offsets = [];
  let stride = 0;
  for (let i = 0; i < header.fields.length; i++) {
    offsets.push(stride);
    stride += (header.size[i] || 4) * (header.count[i] || 1);
  }
  if (off + header.points * stride > bytes.length) throw new Error("PCD data is truncated");
  const dv = new DataView(buffer, off);
  return compactPoints(header.points, hasColor, (i, p) => {
    const base = i * stride;
    p[0] = dv.getFloat32(base + offsets[xi], true);
    p[1] = dv.getFloat32(base + offsets[yi], true);
    p[2] = dv.getFloat32(base + offsets[zi], true);
    if (hasColor) {
      const v = dv.getUint32(base + offsets[ci], true); // packed 0x00RRGGBB (float bits or uint)
      p[3] = ((v >> 16) & 255) / 255;
      p[4] = ((v >> 8) & 255) / 255;
      p[5] = (v & 255) / 255;
    }
  });
}

// Scratch pair for reinterpreting PCD ascii packed-float rgb values.
const _f32 = new Float32Array(1);
const _u32 = new Uint32Array(_f32.buffer);

/* -------------------------------------------------------------- protocol -- */

self.onmessage = (e) => {
  const { id, ext, buffer } = e.data || {};
  try {
    const res = ext === "ply" ? parsePly(buffer) : ext === "pcd" ? parsePcd(buffer) : { unsupported: "extension " + ext };
    if (res.unsupported) {
      // Hand the buffer back (transfer) so the caller can fall back to the
      // vendored three.js loaders without refetching.
      self.postMessage({ id, ok: false, unsupported: res.unsupported, buffer }, [buffer]);
      return;
    }
    const transfer = [res.positions.buffer];
    if (res.colors) transfer.push(res.colors.buffer);
    self.postMessage(
      { id, ok: true, positions: res.positions, colors: res.colors, total: res.total, kept: res.kept },
      transfer
    );
  } catch (err) {
    let back = null;
    try { back = buffer && buffer.byteLength ? buffer : null; } catch (e2) { back = null; }
    self.postMessage(
      { id, ok: false, error: String((err && err.message) || err), buffer: back },
      back ? [back] : []
    );
  }
};
