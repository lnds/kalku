import path from "node:path";

/** The adapter's own directory, from the compiled tests in `build/test`. */
export const ADAPTER = path.resolve(__dirname, "../..");

export const TESTDATA = path.join(ADAPTER, "testdata");

/** The repository's protocol fixtures, outside this package. */
export const FIXTURES = path.resolve(ADAPTER, "../../docs/protocol/fixtures");
