defmodule Kalku.Cast.Started do
  @moduledoc """
  Casting a wekufe in code that runs when the application starts.

  Such code has run by the time a wekufe is loaded, and the tests then pass
  without meeting it. So when a cast's tests ran nothing of the wekufe, the
  application is started again with the wekufe loaded and the tests are run
  once more: what the application builds as it starts is now the wekufe's.

  Starting an application again is not free of its own lie. What
  `test/test_helper.exs` set up after the first start — a sandbox, a mock
  for every process — is gone, and tests fail for that alone. A test that
  fails after the restart is therefore not believed until the same tests
  have passed after a restart with the original code; where they do not,
  the wekufe is not judged at all, and the answer says why.

  The application is started once more with the original code before the
  cast ends, however it ends.
  """

  alias Kalku.Cast.{Entered, Tests}

  @restarted {__MODULE__, :restarted}
  @breaks {__MODULE__, :breaks}

  @doc """
  Judges a wekufe no test ran, by starting the application with it.

  `restore` puts the original code back. Returns the outcome, as
  `Tests.run/2` shapes one.
  """
  def judge(root, tests, watched, site, restore) do
    app = Mix.Project.config()[:app]

    cond do
      not started_by_a_module?(app) -> unrun(site, "")
      breaks = :persistent_term.get(@breaks, nil) -> untried(site, breaks)
      true -> with_the_wekufe(app, root, tests, watched, site, restore)
    end
  end

  @doc """
  Starts the application with whatever code is loaded, when a cast started
  it with a wekufe and has not started it since. True when it is running.
  """
  def settle do
    if :persistent_term.get(@restarted, false) do
      :persistent_term.erase(@restarted)
      Kalku.Runtime.restart(Mix.Project.config()[:app]) == :ok
    else
      true
    end
  end

  defp with_the_wekufe(app, root, tests, watched, site, restore) do
    :persistent_term.put(@restarted, true)

    case Kalku.Runtime.restart(app) do
      :ok ->
        ran = Tests.run(root, tests)
        after_restart(ran, Entered.entered?(watched), root, tests, site, restore)

      {:error, why} ->
        %{outcome: "killed", message: "the application does not start with the wekufe: " <> why}
    end
  end

  # Tests that pass after a restart say the restart cost them nothing.
  defp after_restart(%{outcome: "survived"} = ran, true, _root, _tests, _site, _restore), do: ran

  defp after_restart(%{outcome: "survived"}, _entered, _root, _tests, site, _restore),
    do: unrun(site, ", nor when the application was started again with it")

  defp after_restart(%{outcome: "killed"} = ran, _entered, root, tests, site, restore) do
    restore.()

    case settle() and Tests.run(root, tests) do
      %{outcome: "survived"} ->
        ran

      %{outcome: "killed", killed_by: test} ->
        :persistent_term.put(@breaks, test)
        untried(site, test)

      _ ->
        untried(site, "the suite")
    end
  end

  defp after_restart(ran, _entered, _root, _tests, _site, _restore), do: ran

  # A library has nothing to start again: what it sets up, it sets up when
  # it is first asked.
  defp started_by_a_module?(app) do
    match?({:ok, {_module, _args}}, :application.get_key(app, :mod))
  end

  defp unrun(site, also) do
    %{
      outcome: "no_coverage",
      message:
        "no test ran `#{site["enclosing"]}` while the wekufe was in it#{also}. Either no test " <>
          "reaches it, or it is code that runs once, the first time it is asked for, and that " <>
          "has already happened in the runtime a wekufe is cast in."
    }
  end

  defp untried(site, failing) do
    %{
      outcome: "no_coverage",
      message:
        "no test ran `#{site["enclosing"]}` while the wekufe was in it, and it could not be " <>
          "tried with the application started again: after a restart with no wekufe at all, " <>
          "#{failing} fails, so a test that fails there says nothing of a wekufe. What the " <>
          "tests set up once the application has started is the usual cause."
    }
  end
end
