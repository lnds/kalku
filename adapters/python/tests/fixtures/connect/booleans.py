def f(a, b, c):
    x = a and b
    y = a or b
    z = a and b or c
    return (a or b) and (b or c) and not a
