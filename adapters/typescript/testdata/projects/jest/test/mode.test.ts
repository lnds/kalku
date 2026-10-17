
import { mode } from "../src/mode";

test("mode names the speed", () => {
  expect(mode(true)).toBe("fast");
  expect(mode(false)).toBe("slow");
});
