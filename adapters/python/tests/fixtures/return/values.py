from typing import Protocol


def total(items):
    if not items:
        return 0
    return sum(items)


def nothing():
    return


def already_none(flag):
    if flag:
        return None
    return flag


def side_effect(store) -> None:
    store.save()
    return store.flush()


def numbers(n):
    for i in range(n):
        yield i
    return n


class Account:
    def __init__(self, balance):
        self.balance = balance
        return None

    def withdraw(self, amount):
        def fee():
            return amount // 100

        self.balance -= amount + fee()
        return self.balance

    async def refresh(self, client):
        return await client.get(self.balance)


class Sized(Protocol):
    def size(self) -> int:
        return ...


def make_counter():
    def gen():
        yield 1

    return gen
