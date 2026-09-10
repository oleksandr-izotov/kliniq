/** @type {import('@commitlint/types').UserConfig} */
export default {
  extends: ['@commitlint/config-conventional'],
  // Dependabot writes its own subjects and routinely runs past 100 characters
  // ("bump org.springframework.boot from 3.5.10 to 3.5.16 in /apps/api in the
  // spring group"). The convention is here to keep *our* history readable, and
  // a permanently red check on every bot PR trains everyone to ignore the one
  // check that should mean something.
  ignores: [(message) => /^chore\(deps\): bump /.test(message)],
  rules: {
    'subject-case': [2, 'never', ['upper-case', 'pascal-case']],
    'header-max-length': [2, 'always', 100],
  },
};
