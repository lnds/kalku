
import { settle } from "../src/settle";

test("settle reaches zero", () => {
  expect(settle(3)).toBe(0);
});
