#!/usr/bin/env node
/**
 * Packs the readable DataSonnet sources (config/datastore/*.ds) into the
 * JSON-string files the seed container writes into the DHIS2 datastore
 * (config/datastore/*-mapping.json).
 *
 * The datastore stores each mapping as a single JSON string (the same
 * convention as dhis2/reference-dhis2-tracker-lis-integration's
 * diagnosticReportMap.json), so the .ds source has to be JSON-escaped.
 * Edit the .ds file, run `yarn mappings:pack`, commit both. CI fails if the
 * two drift apart.
 */
import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { dirname, join, basename } from "node:path";
import { fileURLToPath } from "node:url";

const datastoreDir = join(dirname(fileURLToPath(import.meta.url)), "..", "config", "datastore");

for (const file of readdirSync(datastoreDir).filter((f) => f.endsWith(".ds"))) {
  const source = readFileSync(join(datastoreDir, file), "utf8");
  const target = join(datastoreDir, basename(file, ".ds") + ".json");
  writeFileSync(target, JSON.stringify(source) + "\n");
  console.log(`packed ${file} -> ${basename(target)}`);
}
