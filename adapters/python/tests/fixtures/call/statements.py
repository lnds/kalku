import logging

log = logging.getLogger(__name__)


def f(items, store):
    items.append(1)
    store.save(items)
    log.info("saved")
    logging.warning("careful")
    value = store.load()
    print(value)
    return items.copy()


async def g(client):
    await client.send()
    result = await client.fetch()
    return result
