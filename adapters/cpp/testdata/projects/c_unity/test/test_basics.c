#include "ring.h"
#include "unity.h"

static ring r;

void setUp(void) { ring_init(&r); }

void tearDown(void) {}

static void test_a_pushed_item_comes_back(void) {
  int out = 0;
  TEST_ASSERT_TRUE(ring_push(&r, 7));
  TEST_ASSERT_TRUE(ring_pop(&r, &out));
  TEST_ASSERT_EQUAL_INT(7, out);
}

int main(void) {
  UNITY_BEGIN();
  RUN_TEST(test_a_pushed_item_comes_back);
  return UNITY_END();
}
