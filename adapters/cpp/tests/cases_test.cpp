// What Catch2 and doctest say, as the kalku reads it.

#include "cases.hpp"

#include "test.hpp"

using kalku::CaseResult;
using kalku::Framework;
using kalku::Listed;

TEST("an executable is Catch2 by what it answers to --libidentify") {
  CHECK(kalku::says_catch2("description:    A Catch2 test executable\ncategory:       testframework\n"
                           "framework:      Catch2\nversion:        3.16.0\n") == Framework::Catch2);
  CHECK(kalku::says_catch2("description:    A Catch2 test executable\ncategory:       testframework\n"
                           "framework:      Catch Test\nversion:        2.13.10\n") == Framework::Catch2Legacy);
  CHECK(kalku::says_catch2("framework: GoogleTest\nversion: 1.14\n") == Framework::None);
  CHECK(kalku::says_catch2("all tests passed\n") == Framework::None);
  CHECK(kalku::says_doctest("[doctest] doctest version is \"2.5.3\"\n"));
  CHECK(!kalku::says_doctest("Error(s) in input:\n  Unrecognised token: --version\n"));
}

TEST("Catch2 v2 lists one name to a line, and quotes one that starts with #") {
  std::vector<Listed> cases;
  std::string why;
  CHECK(kalku::read_listing(Framework::Catch2Legacy, "Basic options\n\"#hash first\"\nwith, a comma [x]\n", &cases,
                            &why));
  CHECK_EQ(cases.size(), static_cast<std::size_t>(3));
  if (cases.size() == 3) {
    CHECK_EQ(cases[1].name, std::string("#hash first"));
    CHECK_EQ(cases[2].name, std::string("with, a comma [x]"));
  }
}

TEST("a listing that names a case twice is no listing") {
  std::vector<Listed> cases;
  std::string why;
  CHECK(!kalku::read_listing(Framework::Doctest,
                             "<doctest><TestCase name=\"same\" testsuite=\"a\" skipped=\"false\"/>"
                             "<TestCase name=\"same\" testsuite=\"b\" skipped=\"false\"/></doctest>",
                             &cases, &why));
  CHECK(why.find("same") != std::string::npos);
  CHECK(!kalku::read_listing(Framework::Catch2, "not xml at all", &cases, &why));
  CHECK(!kalku::read_listing(Framework::Doctest, "<doctest></doctest>", &cases, &why));
}

TEST("cases are asked for by their exact names") {
  auto catch2 = kalku::run_args(Framework::Catch2, {"~a, [b] *", "plain"}, "out.xml");
  CHECK_EQ(catch2[0], std::string("\\~a\\, \\[b\\] \\*,\\plain"));
  auto doctest = kalku::run_args(Framework::Doctest, {"a, b", "c\\d"}, "out.xml");
  CHECK_EQ(doctest[0], std::string("--test-case=a\\, b,c\\\\d"));
  // No names is the whole executable.
  CHECK(kalku::run_args(Framework::Doctest, {}, "out.xml")[0].rfind("--test-case", 0) != 0);
}

TEST("a report says which case failed, which crashed, and which never ended") {
  auto got = kalku::read_report(Framework::Catch2,
                                "<Catch2TestRun>"
                                "<TestCase name=\"ok\"><OverallResult success=\"true\" durationInSeconds=\"0.25\"/>"
                                "</TestCase>"
                                "<TestCase name=\"bad &amp; sad\"><Expression success=\"false\" type=\"CHECK\" "
                                "filename=\"t.cpp\" line=\"4\"><Original>x == 1</Original><Expanded>2 == 1</Expanded>"
                                "</Expression><OverallResult success=\"false\"/></TestCase>"
                                "<TestCase name=\"boom\"><FatalErrorCondition>SIGSEGV - Segmentation violation "
                                "signal</FatalErrorCondition><OverallResult success=\"false\"/></TestCase>"
                                "<TestCase name=\"cut\"><Expression success=\"true\">");
  CHECK(got["ok"].status == CaseResult::Status::Passed);
  CHECK_EQ(got["ok"].duration_ms, 250L);
  CHECK(got["bad & sad"].status == CaseResult::Status::Failed);
  CHECK(got["bad & sad"].message.find("CHECK( x == 1 ) with 2 == 1 at t.cpp:4") != std::string::npos);
  CHECK(got["boom"].status == CaseResult::Status::Crashed);
  CHECK(got["boom"].message.find("SIGSEGV") != std::string::npos);
  CHECK(got["cut"].status == CaseResult::Status::Crashed);

  auto doc = kalku::read_report(Framework::Doctest,
                                "<doctest><TestSuite>"
                                "<TestCase name=\"ran\"><OverallResultsAsserts test_case_success=\"true\"/></TestCase>"
                                "<TestCase name=\"not asked\" skipped=\"true\"/>"
                                "<TestCase name=\"died\"><Exception crash=\"true\">SIGSEGV</Exception>"
                                "<OverallResultsAsserts test_case_success=\"false\"/></TestCase>"
                                "<TestCase name=\"threw\"><Exception crash=\"false\">nope</Exception>"
                                "<OverallResultsAsserts test_case_success=\"false\"/></TestCase>"
                                "</TestSuite></doctest>");
  CHECK(doc["ran"].status == CaseResult::Status::Passed);
  CHECK(doc.count("not asked") == 0);
  CHECK(doc["died"].status == CaseResult::Status::Crashed);
  CHECK(doc["threw"].status == CaseResult::Status::Failed);
  CHECK_EQ(doc["threw"].message, std::string("it threw: nope"));
}
