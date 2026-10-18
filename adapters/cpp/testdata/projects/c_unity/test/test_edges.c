#include "ring.h"
#include "unity.h"

static ring r;

void setUp(void) { ring_init(&r); }

void tearDown(void) {}

static void test_an_empty_ring_gives_nothing(void) {
  int out = 0;
  TEST_ASSERT_FALSE(ring_pop(&r, &out));
}

static void test_a_full_ring_refuses_more(void) {
  for (int i = 0; i < RING_CAPACITY; i++) TEST_ASSERT_TRUE(ring_push(&r, i));
  TEST_ASSERT_TRUE(ring_full(&r));
  TEST_ASSERT_FALSE(ring_push(&r, 99));
}

static void test_items_come_back_in_order_across_the_end(void) {
  int out = 0;
  for (int i = 0; i < RING_CAPACITY; i++) ring_push(&r, i);
  ring_pop(&r, &out);
  ring_push(&r, 10);
  for (int i = 1; i < RING_CAPACITY; i++) {
    TEST_ASSERT_TRUE(ring_pop(&r, &out));
    TEST_ASSERT_EQUAL_INT(i, out);
  }
  TEST_ASSERT_TRUE(ring_pop(&r, &out));
  TEST_ASSERT_EQUAL_INT(10, out);
}

static void test_no_place_to_put_an_item_is_refused(void) {
  ring_push(&r, 1);
  TEST_ASSERT_FALSE(ring_pop(&r, NULL));
}

int main(void) {
  UNITY_BEGIN();
  RUN_TEST(test_an_empty_ring_gives_nothing);
  RUN_TEST(test_a_full_ring_refuses_more);
  RUN_TEST(test_items_come_back_in_order_across_the_end);
  RUN_TEST(test_no_place_to_put_an_item_is_refused);
  return UNITY_END();
}
