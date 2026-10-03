LIMIT = 5 + 1


def outer(a):
    def inner(b):
        return b > 1

    return inner(a) > 2


class Box:
    size = 3

    def grow(self, by):
        return self.size + by > 4

    class Inner:
        def peek(self, n):
            return n > 5


async def waiter(x):
    return x > 6
