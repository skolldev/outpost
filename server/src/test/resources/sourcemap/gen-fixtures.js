// Generates source-map test fixtures for the Java SourceMapConsumer:
//  - real Angular CLI (esbuild) map + expected lookups from Node's built-in SourceMap
//  - synthetic index map (sections) wrapping two real maps, same treatment
const { SourceMap } = require('node:module');
const fs = require('fs');
const path = require('path');

const dist = '/Users/alex/Documents/repos/outpost/ui/dist/outpost-ui/browser';
const outDir = process.argv[2];
fs.mkdirSync(outDir, { recursive: true });

// Spec-faithful v3 decoder used as a second reference: Node's parser carries a
// segment's name over to following 4-field segments (Chrome-port quirk); the
// spec says 4-field segments have no name. Positions are still cross-checked
// against Node below, so the two references keep each other honest.
function decodeSegments(map) {
  const B = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const out = [];
  const decodeInto = (m, lineOffset, columnOffset, names) => {
    let line = 0, col = 0, src = 0, sl = 0, sc = 0, name = 0, i = 0;
    const s = m.mappings;
    const vlq = () => {
      let r = 0, sh = 0, d;
      do { d = B.indexOf(s[i++]); r += (d & 31) << sh; sh += 5; } while (d & 32);
      const neg = r & 1; r >>>= 1;
      return neg ? -r : r;
    };
    while (i < s.length) {
      const c = s[i];
      if (c === ';') { line++; col = 0; i++; continue; }
      if (c === ',') { i++; continue; }
      const fields = [vlq()];
      while (i < s.length && s[i] !== ';' && s[i] !== ',') fields.push(vlq());
      col += fields[0];
      const outCol = line === 0 ? col + columnOffset : col;
      if (fields.length >= 4) {
        src += fields[1]; sl += fields[2]; sc += fields[3];
        if (fields.length >= 5) name += fields[4];
        out.push({ line: line + lineOffset, col: outCol, sl, sc,
                   name: fields.length >= 5 ? names[name] : null });
      } else {
        out.push({ line: line + lineOffset, col: outCol, sl: null, sc: null, name: null });
      }
    }
  };
  if (map.sections) for (const s of map.sections) decodeInto(s.map, s.offset.line, s.offset.column, s.map.names);
  else decodeInto(map, 0, 0, map.names);
  out.sort((a, b) => a.line - b.line || a.col - b.col);
  return out;
}

function findSegment(segments, line, col) {
  let lo = 0, hi = segments.length - 1, found = null;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    const s = segments[mid];
    if (s.line < line || (s.line === line && s.col <= col)) { found = s; lo = mid + 1; }
    else hi = mid - 1;
  }
  return found;
}

function probes(sm, jsText, mapName) {
  const segments = decodeSegments(sm.payload);
  const lines = jsText.split('\n');
  const out = [];
  for (let line = 0; line < lines.length; line++) {
    const len = lines[line].length;
    const cols = new Set([0, 1, 2, 3]);
    for (let c = 4; c < len; c = Math.ceil(c * 1.03)) cols.add(c);
    if (len > 0) cols.add(len - 1);
    for (const col of cols) {
      if (col >= len) continue;
      const e = sm.findEntry(line, col);
      const s = findSegment(segments, line, col);
      // Cross-check the two references on position; name intentionally comes
      // from the spec-faithful decoder only (see decodeSegments).
      const nodeSource = e.originalSource ?? null;
      const specSource = s && s.sl !== null ? 'seg' : null;
      if ((nodeSource === null) !== (specSource === null)
          || (nodeSource !== null && (e.originalLine !== s.sl || e.originalColumn !== s.sc))) {
        throw new Error(`reference mismatch at ${line}:${col}: node=${JSON.stringify(e)} spec=${JSON.stringify(s)}`);
      }
      out.push({
        // 0-based generated position probed
        generatedLine: line,
        generatedColumn: col,
        // expected original mapping; nulls when no entry found
        originalSource: e.originalSource ?? null,
        originalLine: e.originalLine ?? null,
        originalColumn: e.originalColumn ?? null,
        name: s ? s.name : null,
      });
    }
  }
  console.log(mapName, out.length, 'probes');
  return out;
}

// 1. Real standard map (issue-detail chunk: single source, decorators, async)
const mapFile = fs.readdirSync(dist).find((f) => f.startsWith('chunk-BzEK6nS8') && f.endsWith('.map'));
const jsFile = mapFile.replace('.js.map', '.js');
const map = JSON.parse(fs.readFileSync(path.join(dist, mapFile), 'utf8'));
const js = fs.readFileSync(path.join(dist, jsFile), 'utf8');
fs.copyFileSync(path.join(dist, mapFile), path.join(outDir, 'angular-chunk.js.map'));
fs.copyFileSync(path.join(dist, jsFile), path.join(outDir, 'angular-chunk.js'));
fs.writeFileSync(path.join(outDir, 'angular-chunk.expected.json'),
  JSON.stringify(probes(new SourceMap(map), js, 'angular-chunk'), null, 1));

// 2. Synthetic index map: two real chunk maps concatenated with offsets.
//    Section 2 starts mid-line to exercise column offsets.
const mapB = JSON.parse(fs.readFileSync(path.join(dist, 'chunk-BkU-iEwr.js.map'), 'utf8'));
const jsB = fs.readFileSync(path.join(dist, 'chunk-BkU-iEwr.js'), 'utf8');
const aLines = js.split('\n');
const lastLine = aLines.length - 1;
const lastCol = aLines[lastLine].length;
const combinedJs = js + ';' + jsB;
const indexMap = {
  version: 3,
  file: 'combined.js',
  sections: [
    { offset: { line: 0, column: 0 }, map },
    { offset: { line: lastLine, column: lastCol + 1 }, map: mapB },
  ],
};
fs.writeFileSync(path.join(outDir, 'index-map.js.map'), JSON.stringify(indexMap));
fs.writeFileSync(path.join(outDir, 'index-map.js'), combinedJs);
fs.writeFileSync(path.join(outDir, 'index-map.expected.json'),
  JSON.stringify(probes(new SourceMap(indexMap), combinedJs, 'index-map'), null, 1));
