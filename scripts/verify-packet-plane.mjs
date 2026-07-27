import assert from "node:assert/strict";
import {instantiateKotoba, kotobaArtifact} from
  "../build/packet-plane/packet-plane.mjs";

assert.deepEqual(kotobaArtifact.requiredCapabilities, []);
const {decide, main} = instantiateKotoba();
assert.equal(main(), 100n);

const vectors = [
  [[4n, 60n, 1280n, 1n, 1n], 100n],
  [[6n, 40n, 1280n, 1n, 1n], 100n],
  [[4n, 60n, 1280n, 2n, 0n], 200n],
  [[7n, 60n, 1280n, 1n, 1n], 0n],
  [[4n, 19n, 1280n, 1n, 1n], 10n],
  [[6n, 39n, 1280n, 1n, 1n], 10n],
  [[4n, 60n, 1279n, 1n, 1n], 11n],
  [[4n, 1281n, 1280n, 1n, 1n], 11n],
  [[4n, 60n, 1280n, 1n, 0n], 12n],
  [[4n, 60n, 1280n, 0n, 1n], 13n]
];

for (const [input, expected] of vectors) {
  assert.equal(decide(...input), expected, JSON.stringify(input.map(String)));
}

console.log(`${vectors.length} compiled .kotoba packet decisions verified`);
