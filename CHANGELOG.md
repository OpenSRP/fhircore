# Change Log

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Evaluate CQL-based `initialExpression` (`text/cql-identifier` and `text/cql`) on Questionnaire items using the linked `cqf-library` Library, so CQL-derived defaults prepopulate answers the same way FHIRPath `initialExpression` already does. Skipped when reopening a saved/editable/read-only/summary/draft response. See `android/feature/cql-initial-expression.md`.

## [1.1.1] - 2024-05-20

### Added
- Added the in-app PDF Generation feature
  1. Added a new class (PdfGenerator) for generating PDF documents from HTML content using Android's WebView and PrintManager
  2. Introduced a new class (HtmlPopulator) to populate HTML templates with data from a Questionnaire Response
  3. Implemented functionality to launch PDF generation using a configuration setup
- Added Save draft MVP functionality
- Added Delete saved draft feature
- Add configurable confirmation dialog on form submission

## [1.1.0] - 2024-02-15

### Changed
- Upgrade to latest Android FHIR SDK version includes
    1. an upgrade to the HAPI FHIR libraries used to process StructureMaps. In the previous libraries `$this.id` returned `[ResourceType]/[ID #]`, the new libraries return `[ID #]`. Therefore, any existing StructureMaps that call `$this.id` will need to replace that with `$this.type().name + '/' + $this.id` to have the equivalent output.
    2. changes to Measure evaluation that requires all Measure JSON files to be rebuilt.
    3. change to some [MetadataResources](https://hl7.org/fhir/R5/metadataresource.html) that requires they are referenced by URL and not ID. Any existing content that referes to StructureMaps by ID must be updated to refer to it by URL. If we are not storing a URL for it, we will need to add that. E.g. `Library.url`, `Plandefinition.url` because the `FhirOperator` API uses that field to uniquely identify/retrieve the Metadata resource.
    4. for CQL evaluation, the context is referred to using `%subject` and not `$this`. The latter is reserved for FHIRPath expressions while the  former is used for CQL expressions to refer to the primary subject of the expression e.g. patient.

## [0.2.4] - 2023-06-24
### Added
- Insights feature to show stats on any _Unsynced_ Resources on the device
- Generic date service function to add or subtract days, weeks, months or years from/to current date
- Add ability to configure resource Id used to open profiles
- [App Performance] Profile the time it takes for the app to load the configs and patient data from the local DB
- Add progress indicator during report generation
- Add config property in QuestionnaireConfig to indicate when to udpate or create new extracted resource
- Use `ButtonProperties.buttonType` to configure button size (BIG, MEDIUM or TINY). Deleted `ButtonProperties.smallSized`
- Background worker for closing resources related to copleted Service requets 

### Fixed
- Incorrect error shown for failed authentication when the credentials are invalid.
- [P2P] Possible p2p sync slow regression #2536
- [Event Management] Fix resource closure failure after server sync
- [P2P] Fix for subsequent syncs having records to send

### Changed
- 


## [0.2.3] - 2023-06-24
### Added
- Allow completion of Overdue Tasks
- Allow ordering of registers by related resources last updated
- Invalidation/refresh cache after form completion
- Button background color app configurability
- Use _revinclude when fetching related resources to improve performance
- Refactor handling of questionnaire submission to use generic event bus
- Refactor register search to support configurable forward or reverse include
- Add "Record all" feature in card view
- Functionality to aggregate count for related resources 
- Incremental rendering of content of LIST widget used mostly on profile screen
- The ability to define a regex and separator on the `RulesFactory.RulesEngineService#joinToString(java.util.List<java.lang.String>, java.lang.String, java.lang.String)`
- Use Sentry to track and monitor performance bottlenecks on quest and FHIR Core release app variants
- Add functionality to allow usage of computed rules on DataQuery values
- Allow configs to pass multiple subjects to measure evaluate interface so we can create reports per another resource (e.g. a MeasureReport per Practitioner)
- Introduce an open function on ViewProperties#interpolate to retrieve values from computed values map
- Update the CarePlan Closure configs to define the PlanDefs to closure separately.
- Configurable population resource using ActionParameter
- Migrate build script to Kotlin DSL
- Sorting of resources via rules engine
- Configuration for sorting LIST widget resources
- Closing CarePlans and Related resources configuration update
- Configuration for button content color
- Configuration for button and overflow menu item icons
- [Event Management] Add support for extra resources to close
- Make other-patients name in menu configurable
- Allow computation of configured rules on `QuestionnaireConfig` e.g. for autogenerating a unique ID for Patient registration
- Additional (optional) `ActionParameter` on `QuestionnaireConfig` class for providing extra data to the Questionnaire
- Added a `limitTo` service function that limits lists sizes to a define limit
- Implement configurable image/icon widget
- Added functionality to filter related resources for list view
- Added the sick child workflow automated closure
- Added a new eCBIS flavour to be used for the production app. 

### Fixed
- Logout while offline causes flicker(multiple render) of Login Page
- Allow user to complete overdue task
- Allow launching of overflow menu items (Questionnaire/Profile) 
- Invalidation/refresh cache after form completion
- Successful subsequent login even when PractitionerDetails is not saved 
- Account for dependencies when generating activity schedules
- Add title to Profile pages
- Catch exception when defaultRepository.loadResource tries to load a non-existent resource
- Fix navigate back from Household registers to practitioner profile
- Harmonize launching Questionnaires
- Supply Chain Report Generation fails for some months
- Member icons display wrapping; hiding some texts
- Member icons count on register not tallying with retrieved resources on profile
- Issue with loading related resources
- Fix P2P sync progress showing greater than 100%
- Background worker performance
- The `requested` to `ready` task status update background worker 
- Calculate DUE dates for dependent Tasks
- Triggering QuestionnaireResponse extraction for Questionnaires closed via a Confirm Dialog
- Fix the OVERDUE service status setting on the `RulesFactory#generateTaskServiceStatus()`
- Fix change HH head breaking if a HH head is not already assigned
- `Task.status` for tasks created today and DUE today not update to `ready`

### Changed
- Refactored how the related resources SELECT and COUNT queries search results are represented. 
- RepositoryResourceData now uses two map to represent SELECT/COUNT SQL query results
- Retrieve related resources all at once for resources include via _include SEARCH API
- Enhanced security in Authentication, Authorization and Credentials managagment

## [0.2.2] - 2023-04-17
### Added
- Filter out inactive families using custom search parameters
- Adds support for text-overflow configurability
- Adds Interpolation for planDefinitions property on QuesitonnaireConfig 

### Fixed
- Order the Registers after every form interaction tied to it
- Fixed index already exists exception 
- Minor bug fixes for care plan generation
- Fixes app crash when resourceType is not found

### Changed
- Refactor register search to use SDK Search API to perform database query

## [0.2.1] - 2023-03-27
### Added
- Complete/Cancel CarePlans depending on status of linked Tasks
- Closing/Revoking CarePlans and associated Tasks using configured PlanDefinitions
- Catch all exceptions and return an error message to the user
- Initial Sync progress as a percentage
- Adds internationalization(MLS) for App configs
- Adds register, navigation menus and profile configurations using JSON files
- Implements Practitioner Details
- Integrates Geo Widget
- Implements configurable side menu icons
- Implements resource tagging

### Fixed
- Perform configurable reverse chaining on search
- Geowidget | Fixed a bug disabling the link between registered families and their location
- Geowidget | Fixed a crash when a family location is not found

## [0.0.9 Quest, 0.0.10 EIR, 0.0.4 - ANC] - 2022-07-04, 2021-11-24, 2022-04-01
### Added
- ANC | Updated show search bar to true for family and anc register
- ANC | Added individual measure reporting results to the ResultHome page
- ANC | Update Individual Profile view
- ANC | Added individual measure reporting results to the ResultHome page
- ANC | Update Family Profile view
- ANC | Migrated loading of measure report libraries to the report module from anc details page. Also did some optimizations.
- ANC | Report Measures List Items, Filter Selection, Patient Selection, Compose UI unit tests
- ANC | Report Patient Date Range Selection with year, Patient Search compose UI
- ANC | Report Result Page Individual Patient Item UI update plus Unit Tests
- ANC | Report Result Page For All Population compose UI update plus Unit Tests
- ANC | Remove Family feature
- ANC | Integrate Hilt Dependency Injection
- ANC | Patient details show vital signs
- ANC | Patient details show height, weight and BMI post computing BMI
- ANC | Activate language switcher on profile page
- ANC | Implement vital signs observation extraction from questionnaires
- ANC | Login Add Pin Setup and Login for eCBIS config only
- ANC | Remove Family with Questionnaire Form
- DOC | Adding the CarePlan & Task sample

- Quest | Add the ability to search for patients by id
- Quest | Add ability to view previous QuestionnaireResponse
- Quest | Added workflow configurations
- Quest | Add photo capture questionnaire widget
- Quest | Add ability to edit questionnaire responses
- Quest | Patient registration birthDate from age
- Quest | Integrate Hilt Dependency Injection
- Quest | CQL runner for G6PD
- Quest | Extraction of resources for Patient, Condition, Encounter, Observations
- Quest | Added G6PD login configuration
- Quest | Add Swahili translations for patient registration questionnaire and activate language switcher
- Quest | Swahili localization
- Quest | Added G6PD Patient Details page to show G6PD Status and Test Results
- Quest | Add tasking list
- Quest | Added ability to reference Related Persons and Patients to the Groups representing families
- Quest | Populate family-registration response from DB

- EIR | Added workflow configurations
- EIR | Add Adverse Events feature to log any reactions after the first dose of vaccine using Structure map
- EIR | Integrate Hilt Dependency Injection

- Engine | Integrate Hilt Dependency Injection
- Engine | Fixed login authentication issue.
- Engine | Implement language switcher on profile page
- Engine | Add tasking abstraction
- Engine | Integrate Group resource for family representation
- Engine | Performance improvement when opening questionnaires and saving QRs
- Engine | Add debug mode to optionally load local config
- Engine | Add support for Group.Characteristic model in StructureMap extraction
- Engine | Make the list of resources to be synced via P2P configurable

### Fixed
- ANC | Resolved a bug on measure reporting : App crash when All patient selected
- ANC | Resolved a bug in the Patient details : App crash when computing BMI
- ANC | Refactor measure-report evaluate

- EIR | Resolved a bug in the vaccination flow : App crush when saving vaccine
- EIR | Ability for a complete immunixation flow from Record 1st Vaccine to Full Immunization

- Quest | Fixed test result sorting issues
- Quest | Fixed forms loading smoothly
- Quest | Fixed multiple entries of same test results on quest patient detail screen
- Quest | Fixed mislabeling of questionnaire responses on quest patient detail screen
- Quest | Fix patient registration with estimated age/dob
- Quest | Fix patient register data list items display
- Quest | Fix and reduce the time taken to load patient details page
- Quest | Fix MLS for questionnaire and questionnaire response titles on the patient profile page

- Engine | Fixed app crash when wrong appId is provided
- Engine | Fixed login error message
- Engine | Fixed redirect after logout when press back button
- Engine | Fixed MLS in forms for Android 6(API 23) and below
- Engine | Fixed logout when sync failed

### Changed
- EIR | Updated overdue trigger flow for Vaccine Due date
- EIR | Updated on save vaccine received alert dialogue

- ANC | Removed custom extraction handling and moved Family/ANC forms to structure map
- ANC | Implement CHW ANC BMI questionnaire extraction using StructureMap

- Quest | Updated Sync to sync user specific fhir resources using Search Parameters from assets
- Quest | Updated Patient Details View Configurations and added configurations for routing to Test details page
- Quest | Updated the Questionnaire alert dialog message when the form will add/edit/update.


## [0.0.2 Quest, 0.0.3 - ANC] - 2021-10-27

### Added
- ANC | Optimized memory use for measure reporting by using String builder File forgotten in PR 646
- ANC | Optimized memory use for measure reporting by using String builder and loading only once cql and measure reporting libraries
- ANC | Added progress bar to cql and measure reporting in ANC patien details page
- ANC | Added measure reporting to ANC application
- Engine | Added class for Measure report evaluation which will be used in ANC application
- ANC | Added Condition resource to sync params list
- Moved Token to secure storage from AccountManager
- Expose [custom user attribute](https://www.keycloak.org/docs/latest/server_admin/index.html#_user-attributes) `questionnaire_publisher` available in SharedPreferences with key `USER_QUESTIONNAIRE_PUBLISHER` (#607)
- Quest | Filter [Questionnaires](http://hl7.org/fhir/questionnaire.html) by [publisher](http://hl7.org/fhir/questionnaire-definitions.html#Questionnaire.publisher) using user attribute as per above. (#571)
- Quest | Patient List, Load Config from server
- Quest | Added Patient Profile View
- Quest | Patient Registration Questionnaire
- Quest | Test Results Questionnaire
- Quest | Feedback on UI
- Quest | Remove custom activity
- Quest | Add tags to patient via questionnaire
- Engine | AlertDialog on questionnaire activity load, back, submit, progress
- Engine | Block questionnaire submit if validation errors exist
- Quest | Bottom Navigation updated with new structure
- Quest | User actions, profile screen implemented
- Engine | print patient age as #y #m or #m #d
- ANC | Navigation structure changes implemented
- ANC | Bottom sheet for switching registers added
- ANC | User actions, profile screen implemented
- ANC | Drawer menu removed

### Fixed

- Added dependecies that were missing and causing CQL Evaluation to fail
- Out of memory issue on few tests
- Authentication toekn expiry issue
- Fhir Resource Converter issue after resource update
- Inteceptor handling for missing account
- Engine: Fixes Structure map based resource extraction not working | Remove the menu icon on the login screen
- ANC | Fix crashes due to invalid data
- Engine | Remove the menu icon on the login screen
- Engine: Fixes Structure map based resource extraction not working
- eCBIS | Login page updated

### Changed

-

## [0.0.9 EIR, 0.0.2 - ANC] - 2021-09-27

### Added

- Family Registeration with Tags from SDK fix for tags and profile
- Add edit patient registration feature. This enables you to modify the patient and related person's details
- Add Family Profile View
- Add Past Encounters Screen
- Add missing properties in the COVAX Immunization Resource
- New mockup changes incorporated
- Added class for Measure report evaluation which will be used in ANC application
- Added Logic for CQL Evaluation in ANC App. This include, AncDetailsFragment,ViewModel & Tests
- Added CQL Lib Evaluator class for CQl Expressions

### Changed

- Refactor COVAX Immunization extraction to use StructureMap-based extractio
- eCBIS | Side menu changed

## [0.0.8 EIR, 0.0.1 - ANC] - 2021-09-11

### Added

- Add related person to patient registration
- Integrate StructureMap-based extraction
- ANC register
- Load Questionnaire from DB
- Search by WHO identifier
- Sync progress loader and datetime
- ANC details activity
- Enroll into ANC and add Encounter, Observation, Episode of Care, Condition

### Fixed

- Fixed empty patient view layout position
- Fixed bug on patients not populated on fresh install
- Fixed the sync process did not let loader open on list patients unless sync is completed

### Changed

- Changed patient registration extraction to use StructureMap-based extraction

## [0.0.7 EIR] - 2021-07-28

### Added

- Show progress bar on login attempt

### Fixed

- Fixed bug on barcode crash
- Fixed bug on record vaccine
- Fixed bug on patient list last seen date

## [0.0.6 EIR] - 2021-07-15

### Added

- Gender input changed to radio selection on client info screen
- Display message on empty list along with new client register button
- Client count display in drawer
- Login and Keycloak authentication for FHIR APIs
- Added user logout functionality
- Vaccine status stylign in list view and details view

### Fixed

- Select initial/default value of Radio Button on client info screen
- Fixed patient list pagination button position to static on bottom of list
- Fixed patient list fragment adapter count
- Fixed bug on search result on patient list

### Changed

- Reduce font size and spacing on patient list screen
- Removed covax prefix and postfix in general use-case

## [0.0.3] - 2021-05-24

### Added

- Extract and assign Patient ID from Questionnaire field
- Build barcode scanner
- Track patient vaccine status
- Display immunization info on the Patient Profile
- Enable patient overdue toggle filter
