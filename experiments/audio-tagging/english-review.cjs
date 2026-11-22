'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {decodeWav, openDiscogsEmbedder} = require('./probe.cjs');
const {unit, cosine, writeListeningPage} = require('./similarity-pilot.cjs');
const {proposeGenres, DISCOGS_METADATA_SHA} = require('./train-clips.cjs');

const REVIEW_TRACKS = 50;
const BLIND_CASES = 50;

function genreOrder(tracks) {
  const groups = Object.values(Object.groupBy(tracks, track => track.tag));
  const ordered = [];
  for (let index = 0; index < Math.max(...groups.map(group => group.length)); index++) {
    for (const group of groups) if (group[index]) ordered.push(group[index]);
  }
  return ordered;
}

function blindCases(tracks) {
  const cases = [];
  const totals = {eligible: 0, sameFirstPick: 0, differentFirstPick: 0};
  for (const anchor of tracks) {
    const peers = tracks.filter(track => track.artist !== anchor.artist && track.tag === anchor.tag);
    if (peers.length < 2) continue;
    totals.eligible++;
    // Catalogue-only ties are stable by track ID, as in the first pilot.
    const catalogue = peers.slice().sort((left, right) => left.id.localeCompare(right.id, 'en'))[0];
    const audio = peers.reduce((closest, track) =>
      cosine(anchor.first, track.first) > cosine(anchor.first, closest.first) ? track : closest);
    if (catalogue.id === audio.id) {
      totals.sameFirstPick++;
      continue;
    }
    totals.differentFirstPick++;
    if (cases.length === BLIND_CASES) continue;
    const id = cases.length + 1;
    const swap = createHash('sha256').update(anchor.id).digest()[0] < 128;
    cases.push({id, kind: 'similar', anchors: [anchor.id],
      a: (swap ? audio : catalogue).id, b: (swap ? catalogue : audio).id, audioOption: swap ? 'a' : 'b'});
  }
  return {cases, totals};
}

async function main() {
  const [manifest, model, metadata] = process.argv.slice(2);
  if (!metadata) throw Error('Usage: node english-review.cjs MANIFEST.json DISCOGS.onnx DISCOGS.json');
  const directory = path.dirname(path.resolve(manifest));
  const modelMetadata = fs.readFileSync(metadata);
  if (createHash('sha256').update(modelMetadata).digest('hex') !== DISCOGS_METADATA_SHA) {
    throw Error('Discogs metadata checksum mismatch');
  }
  const {classes} = JSON.parse(modelMetadata);
  const tracks = [];
  const embedder = await openDiscogsEmbedder(model);
  try {
    for (const track of JSON.parse(fs.readFileSync(manifest, 'utf8'))) {
      if (track.language !== 'English' || track.clips.length !== 2) continue;
      const outputs = [];
      for (const clip of track.clips) {
        const samples = decodeWav(fs.readFileSync(path.join(directory, clip)), 0, 15);
        outputs.push(await embedder.extract(samples, true));
      }
      if (outputs.some(output => !output)) continue;
      const first = unit(outputs[0].embedding);
      if (!first) continue;
      tracks.push({...track, first, suggestions: proposeGenres(classes, outputs.map(output => output.styles))});
      if (tracks.length % 10 === 0) console.log('Analyzed:', tracks.length);
    }
  } finally {
    await embedder.close();
  }

  const ordered = genreOrder(tracks);
  const {cases, totals} = blindCases(ordered);
  const catalogue = ordered.map(({first, ...track}) => track);
  const review = catalogue.slice(0, REVIEW_TRACKS);
  const counts = {
    tracks: tracks.length,
    artists: new Set(tracks.map(track => track.artist)).size,
    byGenre: Object.fromEntries(Object.entries(Object.groupBy(tracks, track => track.tag))
      .map(([tag, entries]) => [tag, entries.length])),
    withSuggestions: tracks.filter(track => track.suggestions.length).length,
    reviewTracks: review.length,
    labelsToReview: review.reduce((sum, track) => sum + track.suggestions.length, 0),
    blindCases: cases.length,
    ...totals
  };
  const evaluation = 'english-holdout-v1';
  writeListeningPage(path.join(directory, 'blind-listening-english.html'), {
    model: evaluation, cases, catalogue, header: '共鸣 · 英语歌相似推荐盲听',
    instructions: '每题先听参考歌曲，再选听起来更像的候选。觉得差不多就选「差不多」。两种方法的选歌顺序打乱，可以分批听。'
  });
  const template = fs.readFileSync(path.join(__dirname, 'genre-review.html'), 'utf8');
  const data = JSON.stringify({evaluation, review, counts}).replaceAll('<', '\\u003c');
  fs.writeFileSync(path.join(directory, 'genre-review-english.html'),
    template.replace('/*__REVIEW_DATA__*/', () => 'const pilot = ' + data + ';'));
  fs.writeFileSync(path.join(directory, 'english-review-report.json'),
    JSON.stringify({evaluation, counts, cases, catalogue}, null, 2));
  console.log(JSON.stringify(counts, null, 2));
}

if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
