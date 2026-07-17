import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import { createServer } from 'vite'
import { canonicalStringify } from './canonical-json.mjs'

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const schemaPath = resolve(frontendRoot, 'schema/portfolio-import-v1.schema.json')

export const assertValidPortfolioPayload = async (payload) => {
  const schema = JSON.parse(await readFile(schemaPath, 'utf8'))
  const ajv = new Ajv2020({ allErrors: true, strict: true })
  addFormats(ajv)
  const validate = ajv.compile(schema)
  if (!validate(payload)) {
    throw new Error(`portfolio schema validation failed: ${ajv.errorsText(validate.errors)}`)
  }
}

export const exportPortfolio = async ({ outputPath }) => {
  const server = await createServer({
    root: frontendRoot,
    appType: 'custom',
    logLevel: 'error',
    server: { middlewareMode: true },
  })
  try {
    const module = await server.ssrLoadModule('/src/data/portfolio.ts')
    const payload = {
      schemaVersion: 1,
      identity: module.identity,
      heroAsset: module.heroAsset,
      projectAssets: module.projectAssets,
      portfolioContent: module.portfolioContent,
    }
    await assertValidPortfolioPayload(payload)
    const bytes = canonicalStringify(payload)
    const absoluteOutput = resolve(frontendRoot, outputPath)
    await mkdir(dirname(absoluteOutput), { recursive: true })
    await writeFile(absoluteOutput, bytes, 'utf8')
    return {
      outputPath: absoluteOutput,
      sha256: createHash('sha256').update(bytes, 'utf8').digest('hex'),
    }
  } finally {
    await server.close()
  }
}

const outputFlag = process.argv.indexOf('--output')
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (outputFlag < 0 || !process.argv[outputFlag + 1]) {
    throw new Error('usage: node scripts/export-portfolio.mjs --output OUTPUT_PATH')
  }
  const result = await exportPortfolio({ outputPath: process.argv[outputFlag + 1] })
  process.stdout.write(`${JSON.stringify(result)}\n`)
}
