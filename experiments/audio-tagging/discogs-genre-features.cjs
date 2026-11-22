'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {decodeWav, openDiscogsEmbedder} = require('./probe.cjs');
const {unit} = require('./similarity-pilot.cjs');

async function main() {
  const [directory, model] = process.argv.slice(2);
  if (!model) throw Error('Usage: node discogs-genre-features.cjs REVIEW_DIRECTORY DISCOGS.onnx');
  const manifest = JSON.parse(fs.readFileSync(path.join(directory, 'similarity-input.json'), 'utf8'));
  const embedder = await openDiscogsEmbedder(model);
  const tracks = {};
  try {
    for (const {id, clips} of manifest) {
      const windows = [];
      for (const clip of clips.slice(0, 2)) {
        const waveform = decodeWav(fs.readFileSync(path.join(directory, clip)), 0, 15);
        windows.push(await embedder.extract(waveform, true));
      }
      const [first, second] = windows;
      if (!first || !second) continue;
      const embedding = unit(first.embedding.map((value, i) => (value + second.embedding[i]) / 2));
      if (!embedding) continue;
      tracks[id] = {embedding, styles: first.styles.map((value, i) => (value + second.styles[i]) / 2)};
      if (Object.keys(tracks).length % 10 === 0) console.log('Extracted:', Object.keys(tracks).length);
    }
  } finally {
    await embedder.close();
  }
  fs.writeFileSync(path.join(directory, 'discogs-genre-features.json'), JSON.stringify(tracks));
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
