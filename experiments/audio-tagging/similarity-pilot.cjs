'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {analyze, decodeWav, openDiscogsEmbedder} = require('./probe.cjs');

function unit(vector) {
  const length = Math.hypot(...vector);
  return length > 0 && Number.isFinite(length) ? vector.map(value => value / length) : null;
}

const cosine = (left, right) => left.reduce((sum, value, index) => sum + value * right[index], 0);
const lexical = (left, right) => left.id.localeCompare(right.id, 'en');

function comparisons(tracks) {
  const cases = [];
  const usedAnchors = new Set();
  const add = (kind, anchors, tagOnly, withAudio) => {
    if (tagOnly.id === withAudio.id) return;
    const id = cases.length + 1;
    const swap = id % 2 === 0;
    cases.push({id, kind, anchors: anchors.map(track => track.id),
      a: (swap ? withAudio : tagOnly).id, b: (swap ? tagOnly : withAudio).id, audioOption: swap ? 'a' : 'b'});
  };

  for (const anchor of tracks) {
    if (cases.length >= 8 || usedAnchors.has(anchor.artist)) continue;
    const peers = tracks.filter(track => track.artist !== anchor.artist && track.tag === anchor.tag).sort(lexical);
    if (peers.length < 2) continue;
    const closest = peers.reduce((best, track) => cosine(anchor.sound, track.sound) > cosine(anchor.sound, best.sound) ? track : best);
    const count = cases.length;
    add('similar', [anchor], peers[0], closest);
    if (cases.length > count) usedAnchors.add(anchor.artist);
  }

  const tags = [...new Set(tracks.map(track => track.tag))];
  for (const tag of tags) {
    if (cases.filter(entry => entry.kind === 'direction').length >= 4) break;
    const artists = new Set();
    const anchors = tracks.filter(track => {
      if (track.tag !== tag || artists.has(track.artist)) return false;
      artists.add(track.artist);
      return true;
    }).slice(0, 4);
    const peers = tracks.filter(track => track.tag !== tag && anchors.every(anchor => anchor.artist !== track.artist)).sort(lexical);
    if (anchors.length !== 4 || peers.length < 2) continue;
    const meanSimilarity = track => anchors.reduce((sum, anchor) => sum + cosine(track.sound, anchor.sound), 0) / anchors.length;
    const farthest = peers.reduce((best, track) => meanSimilarity(track) < meanSimilarity(best) ? track : best);
    add('direction', anchors, peers[0], farthest);
  }
  return cases;
}

function evaluateGenrePrototypes(tracks) {
  let tested = 0;
  let majorityHits = 0;
  let audioHits = 0;
  for (const artistTracks of Object.values(Object.groupBy(tracks, track => track.artist))) {
    const training = tracks.filter(track => track.artist !== artistTracks[0].artist);
    const groups = Object.groupBy(training, track => track.tag);
    const prototypes = Object.entries(groups).map(([tag, songs]) => ({tag, count: songs.length,
      sound: unit(songs[0].sound.map((_, index) => songs.reduce((sum, song) => sum + song.sound[index], 0)))}))
      .filter(prototype => prototype.sound).sort((left, right) => right.count - left.count || left.tag.localeCompare(right.tag, 'en'));
    for (const track of artistTracks) {
      if (!prototypes.some(prototype => prototype.tag === track.tag) || prototypes.length < 2) continue;
      const closest = prototypes.reduce((best, prototype) =>
        cosine(track.sound, prototype.sound) > cosine(track.sound, best.sound) ? prototype : best);
      tested++;
      majorityHits += Number(prototypes[0].tag === track.tag);
      audioHits += Number(closest.tag === track.tag);
    }
  }
  return {tested, majorityHits, audioHits, noTrainingExamples: tracks.length - tested};
}

async function main() {
  const args = process.argv.slice(2);
  const discogs = args[0] === '--discogs';
  const [manifestPath, model, metadata] = discogs ? args.slice(1) : args;
  if (!manifestPath || !model || (!discogs && !metadata)) {
    throw Error('Usage: node similarity-pilot.cjs MANIFEST.json MODEL.onnx MODEL.json | --discogs MANIFEST.json MODEL.onnx');
  }
  const directory = path.dirname(path.resolve(manifestPath));
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const tracks = [];
  const embedder = discogs ? await openDiscogsEmbedder(model) : null;
  try {
    for (const track of manifest) {
      const windows = [];
      for (const clip of track.clips) {
        const samples = decodeWav(fs.readFileSync(path.join(directory, clip)), 0, 15);
        const report = embedder ? await embedder.extract(samples) : await analyze(samples, model, metadata, false, true);
        const embedding = report?.embedding;
        const vector = embedding && unit(embedding);
        if (vector) windows.push(vector);
      }
      if (windows.length < 2) continue;
      const sound = unit(windows[0].map((value, index) => value + windows[1][index]));
      if (sound) tracks.push({...track, sound, windows, windowAgreement: cosine(windows[0], windows[1])});
    }
  } finally {
    if (embedder) await embedder.close();
  }

  const cases = comparisons(tracks);
  const genrePrototypes = {
    all: evaluateGenrePrototypes(tracks),
    english: evaluateGenrePrototypes(tracks.filter(track => track.language === 'English'))
  };
  const byLanguage = Object.groupBy(tracks, track => track.language);
  // Search from one excerpt across second excerpts. The catalogue labels are not used.
  const repeatRecordingTop1 = tracks.filter(track => tracks.every(candidate => candidate.id === track.id ||
    track.windowAgreement > cosine(track.windows[0], candidate.windows[1]))).length;
  const result = {
    model: discogs ? 'discogs-effnet-bsdynamic-1' : 'msd-musicnn-1',
    tracks: tracks.length,
    artists: new Set(tracks.map(track => track.artist)).size,
    byLanguage: Object.fromEntries(Object.entries(byLanguage).map(([language, entries]) => [language, entries.length])),
    byGenre: Object.fromEntries(Object.entries(Object.groupBy(tracks, track => track.tag)).map(([tag, entries]) => [tag, entries.length])),
    meanWindowAgreement: tracks.length ? tracks.reduce((sum, track) => sum + track.windowAgreement, 0) / tracks.length : null,
    genrePrototypes, repeatRecordingTop1, cases, catalogue: tracks.map(({sound, windows, ...track}) => track)
  };
  const suffix = discogs ? '-discogs' : '';
  fs.writeFileSync(path.join(directory, `similarity-report${suffix}.json`), JSON.stringify(result, null, 2));
  writeListeningPage(path.join(directory, `blind-listening${suffix}.html`), {model: result.model, cases, catalogue: result.catalogue});
  console.log(JSON.stringify({model: result.model, tracks: result.tracks, artists: result.artists, byLanguage: result.byLanguage,
    byGenre: result.byGenre, meanWindowAgreement: result.meanWindowAgreement, genrePrototypes, repeatRecordingTop1,
    cases: Object.fromEntries(Object.entries(Object.groupBy(cases, item => item.kind)).map(([kind, items]) => [kind, items.length]))}, null, 2));
}

function writeListeningPage(destination, data) {
  const template = fs.readFileSync(path.join(__dirname, 'blind-listening.html'), 'utf8');
  fs.writeFileSync(destination, template.replace('/*__PILOT_DATA__*/', () =>
    'const pilot = ' + JSON.stringify(data).replaceAll('<', '\\u003c') + ';'));
}

module.exports = {comparisons, evaluateGenrePrototypes, unit, cosine, writeListeningPage};
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
