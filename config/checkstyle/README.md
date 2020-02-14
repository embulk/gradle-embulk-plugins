Checkstyle for the Embulk project
==================================

* google_check.xml: Downloaded from: https://github.com/checkstyle/checkstyle/blob/master/src/main/resources/google_checks.xml
     * Commit: e145aa1c2829f1bc3cccb879dd5197a4904f5687
* checkstyle.xml: Customized from google_check.xml.
    * To enable suppressions with @SuppressWarnings.
    * To accept package names with underscores.
    * To indent with 4-column spaces.
    * To limit columns to 180 characters, which will be shortened later.
    * To reject unused imports.
