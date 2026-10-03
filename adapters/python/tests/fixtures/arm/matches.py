def kind(n):
    match n:
        case 0:
            return "zero"
        case 1 | 2:
            return "few"
        case _:
            return "many"


def only(n):
    match n:
        case _:
            return 1
