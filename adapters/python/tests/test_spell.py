from kalku_python import spell


def test_every_spell_kalku_casts_is_a_spell_of_the_shared_catalogue():
    assert set(spell.CAST) <= set(spell.ALL)
    assert spell.CAST == ("arm", "compare", "connect", "negate", "literal", "call")


def test_a_name_is_a_spell_or_it_is_not():
    for name in spell.ALL:
        assert spell.parse(name) == name
    assert spell.parse("mutate") is None
    assert spell.parse("") is None
    assert spell.parse("Arm") is None


def test_the_concurrency_spells_are_in_the_catalogue_and_not_cast():
    assert {"await", "supervise", "foreign"} <= set(spell.ALL)
    assert not {"await", "supervise", "foreign"} & set(spell.CAST)
