(ns holy-lambda.tasks
  "holy-lambda cmd application"
  (:require
   [clojure.string :as s]
   [clojure.pprint :as pprint]
   [clojure.java.shell :as csh]
   [clojure.edn :as edn]
   [cheshire.core :as json]
   [babashka.tasks :as tasks]
   [babashka.deps :as deps]
   [babashka.fs :as fs]
   [babashka.curl :as curl]
   [babashka.process :as p]
   [clojure.java.io :as io]))


(def TASK_NAME (or (resolve 'babashka.tasks/*-task-name*)
                   (resolve 'babashka.tasks/*task-name*)))

;; Taken from clojure-term-colors https://github.com/trhura/clojure-term-colors
(defn- escape-code
  [i]
  (str "\033[" i "m"))

(def ^:dynamic *colors*
  "foreground color map"
  (zipmap [:grey :red :green :yellow
           :blue :magenta :cyan :white]
          (map escape-code
               (range 30 38))))

(def ^:dynamic *highlights*
  "background color map"
  (zipmap [:on-grey :on-red :on-green :on-yellow
           :on-blue :on-magenta :on-cyan :on-white]
          (map escape-code
               (range 40 48))))

(def ^:dynamic *attributes*
  "attributes color map"
  (into {}
        (filter (comp not nil? key)
                (zipmap [:bold, :dark, nil, :underline,
                         :blink, nil, :reverse-color, :concealed]
                        (map escape-code (range 1 9))))))

(def ^:dynamic *reset* (escape-code 0))

;; Bind to true to have the colorize functions not apply coloring to
;; their arguments.
(def ^:dynamic *disable-colors* nil)

(defmacro define-color-function
  "define a function `fname' which wraps its arguments with
        corresponding `color' codes"
  [fname color]
  (let [fname (symbol (name fname))
        args (symbol 'args)]
    `(defn ~fname [& ~args]
       (if-not *disable-colors*
         (str (clojure.string/join (map #(str ~color %) ~args)) ~*reset*)
         (apply str ~args)))))

(defn define-color-functions-from-map
  "define functions from color maps."
  [colormap]
  (eval `(do ~@(map (fn [[color escape-code]]
                      `(println ~color ~escape-code)
                      `(define-color-function ~color ~escape-code))
                    colormap))))

(define-color-functions-from-map *colors*)
(define-color-functions-from-map *highlights*)
(define-color-functions-from-map *attributes*)

;; Taken from clojure-term-colors https://github.com/trhura/clojure-term-colors

;;;; [START] HELPERS

(defn norm-args
  [args]
  (into {} (mapv
            (fn [[k v]]
              [(cond-> k
                 (s/includes? k ":")
                 (subs 1)

                 true keyword)
               (or v true)])
            (partition-all 2 args))))

(defn- exit-non-zero
  [proc]
  (when-let [exit-code (some-> proc deref :exit)]
    (when (not (zero? exit-code))
      (System/exit exit-code))))

(defn- shell
  [cmd & args]
  (exit-non-zero (p/process (into (p/tokenize cmd) (remove nil? args)) {:inherit true})))

(defn- clojure [cmd & args]
  (exit-non-zero (deps/clojure (into (p/tokenize cmd) args))))

(defn accent
  [s]
  (underline (blue s)))

(defn hpr
  [& args]
  (apply println (accent "[holy-lambda]") args))

(defn pre
  [s]
  (red s))

(defn prw
  [s]
  (yellow s))

(defn prs
  [s]
  (green s))

(defn shsp
  [cmd & args]
  (let [result (apply csh/sh (remove nil? (into (p/tokenize cmd) args)))]
    (if (s/blank? (:err result))
      (when-not (s/blank? (:out result))
        (println (prs (:out result))))
      (println (pre (:err result))))))

(defn shs
  [cmd & args]
  (let [result (apply csh/sh (remove nil? (into (p/tokenize cmd) args)))]
    (if (s/blank? (:err result))
      (when-not (s/blank? (:out result))
        (:out result))
      (:err result))))

(defn command-exists?
  [cmd]
  (= (int (:exit (csh/sh "which" cmd))) 0))

(defn plist
  [xs]
  (s/join "" (mapv (fn [x]
                     (str " - " x "\n"))
                   xs)))
;;;; [END] HELPERS

(def AVAILABLE_RUNTIMES #{:babashka :native :java})
(def AVAILABLE_REGIONS #{"us-east-2", "us-east-1", "us-west-1", "us-west-2", "af-south-1", "ap-east-1", "ap-south-1", "ap-northeast-3", "ap-northeast-2", "ap-southeast-1", "ap-southeast-2", "ap-northeast-1", "ca-central-1", "cn-north-1", "cn-northwest-1", "eu-central-1", "eu-west-1", "eu-west-2", "eu-south-1", "eu-west-3", "eu-north-1", "me-south-1", "sa-east-1"})
(def REMOTE_TASKS "https://raw.githubusercontent.com/FieryCod/holy-lambda/master/modules/holy-lambda-babashka-tasks/src/holy_lambda/tasks.clj")
(def TASKS_VERSION "0.0.1")
(def TASKS_VERSION_MATCH #"(?:TASKS_VERSION) (\"[0-9]*\.[0-9]*\.[0-9]*\")")
(def BUCKET_IN_LS_REGEX #"(?:[0-9- :]+)(.*)")

(defn options
  []
  (:holy-lambda/options (edn/read-string (slurp (io/file "bb.edn")))))

(def OPTIONS
  (try
    (options)
    (catch Exception err_
      (hpr (pre "Either bb.edn not found or does not contain :holy-lambda/options"))
      (System/exit 1))))

(defn stat-file
  [filename]
  (when-not (fs/exists? (io/file filename))
    (hpr "PATH" (accent filename) "does not exists.. Exiting!")
    (System/exit 1)))

(alter-var-root #'babashka.tasks/-log-info
                (fn [f]
                  (fn [& strs]
                    (hpr (str "Command " (red "<") (accent @TASK_NAME) (red ">"))))))

(def STACK (:stack OPTIONS))
(def DEFAULT_ENVS_FILE
  (try
    (if-not (fs/exists? (io/file (:envs STACK)))
      (throw (Exception. "."))
      (:envs STACK))
    (catch Exception err_
      (hpr (pre "File envs.json for aws sam not found.. Exiting!\n
Check https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/serverless-sam-cli-using-invoke.html#serverless-sam-cli-using-invoke-environment-file"))
      (System/exit 1))))

(def IMAGE_CORDS
  (case (:build-variant OPTIONS)
    :ce "fierycod/graalvm-native-image:ce"
    :ee "fierycod/graalvm-native-image:ee"
    (do (hpr (pre "Incorrect build variant choosen:")
             (str (accent (:build-variant OPTIONS)) (pre "."))
             (pre "Choose either") (accent ":ce") (pre "or") (accent ":ee") (pre "build variant!"))
        (System/exit 1))))

(def INFRA (:infra OPTIONS))
(def RUNTIME (:runtime OPTIONS))
(def RUNTIME_NAME (:name RUNTIME))
(def BUCKET_PREFIX (:bucket-prefix INFRA))
(def BUCKET_NAME (:bucket-name INFRA))
(def REGION (:region INFRA))
(def DEFAULT_LAMBDA_NAME (:default-lambda STACK))
(def RUNTIME_VERSION (:version RUNTIME))
(def ENTRYPOINT (:entrypoint (:runtime OPTIONS)))
(def OUTPUT_JAR_PATH ".holy-lambda/build/output.jar")
(def OUTPUT_JAR_PATH_RELATIVE "build/output.jar")
(def HOLY_LAMBDA_DEPS_PATH ".holy-lambda/clojure/deps.edn")
(def STACK_NAME (:name STACK))
(def TEMPLATE_FILE (:template STACK))
(def REQUIRED_COMMANDS ["aws" "sam" "bb" "docker" "clojure" "zip" "id" "clj-kondo"])
(def CAPABILITIES (if-let [caps (seq (:capabilities STACK))]
                    caps
                    nil))
(def MODIFIED_TEMPLATE_FILE ".holy-lambda/template.yml")
(def PACKAGED_TEMPLATE_FILE ".holy-lambda/packaged.yml")
(def BABASHKA_RUNTIME_LAYER_FILE ".holy-lambda/babashka-runtime/template.yml")
(def DEPLOY_DEPENDANTS? (:auto-deploy-dependants? OPTIONS))

(defn -create-bucket
  [& [bucket]]
  (shsp "aws" "s3" "mb" (str "s3://" (or bucket BUCKET_NAME))))

(defn -remove-bucket
  [& [bucket]]
  (shsp "aws" "s3" "rb"
        "--force" (str "s3://" (or bucket BUCKET_NAME))
        "--region" REGION))

(def USER_GID
  (str (s/trim (shs "id -u"))
       ":" (s/trim (shs "id -g"))))

(def HOME_DIR
  (.getAbsolutePath
   (io/file (or
             (System/getenv "XDG_CACHE_HOME")
             (System/getProperty "user.home")))))

(def AWS_DIR
  (.getAbsolutePath (io/file HOME_DIR ".aws")))

(defn edn->pp-sedn
  [edn]
  (with-out-str (pprint/pprint edn)))

(defn map->parameters-inline
  [m]
  (s/join " " (mapv (fn [[k v]]
                      (str "ParameterKey=" k ",ParameterValue=" v))
                    m)))

(defn buckets
  []
  (set (mapv (fn [b] (some-> (re-find BUCKET_IN_LS_REGEX b) second))
             (s/split (shs "aws" "s3" "ls") #"\n"))))

(defn bucket-exists?
  []
  (contains? (buckets) BUCKET_NAME))

(defn parameters--java
  [opt]
  {"CodeUri" (if (= :relative opt)
               OUTPUT_JAR_PATH_RELATIVE
               OUTPUT_JAR_PATH)
   "Runtime" "java8"
   "Entrypoint" ENTRYPOINT})

(defn parameters--babashka
  [opt]
  {"CodeUri" (if (= :relative opt)
               "../src"
               "src")
   "Runtime" "provided"
   "Entrypoint" ENTRYPOINT})

(defn -parameters
  [& [opt]]
  (case RUNTIME_NAME
    :java (parameters--java opt)
    :babashka (parameters--babashka opt)))

(defn parameters
  [& [opt]]
  ((if (= opt :toml)
     (throw (ex-info ":toml not supported for now!"))
    ;; map->parameters-toml
    map->parameters-inline)
   (-parameters)))

(defn docker:run
  "     \033[0;31m>\033[0m Run command in \033[0;31mfierycod/graalvm-native-image\033[0m docker context"
  [command]
  (shell "docker run --rm"
         "-e" "AWS_CREDENTIAL_PROFILES_FILE=/project/.aws/credentials"
         "-e" "AWS_CONFIG_FILE=/project/.aws/config"
         "-v" (str (.getAbsolutePath (io/file "")) ":/project")
         "-v" (str AWS_DIR ":" "/project/.aws:ro")
         "--user" USER_GID
         "-it" IMAGE_CORDS
         "/bin/bash" "-c" command)
  (shell "rm -Rf .aws"))

(defn native:compile
  "     \033[0;31m>\033[0m Compiles \033[0;31mtarget/output.jar\033[0m with \033[0;31mnative-image\033[0m to native"
  []
  )

(defn deps-sync--babashka
  []
  (when (= RUNTIME_NAME :babashka)
    (when-not (empty? (:pods (:runtime OPTIONS)))
      (hpr "Babashka pods found! Syncing" (str (accent "babashka pods") ".") "Pods should be distributed via a layer which points to" (accent ".holy-lambda/pods"))
      (docker:run "download_pods")
      (when (fs/exists? (io/file ".holy-lambda/.babashka"))
        (shell "rm -Rf .holy-lambda/pods")
        (shell "mkdir -p .holy-lambda/pods")
        (shell "cp -R .holy-lambda/.babashka .holy-lambda/pods/")))))

(def -babashka-runtime-layer-template
"AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31
Description: >
  Babashka runtime as an AWS::Serverless::Application

Resources:
  HolyLambdaBabashkaRuntime:
    Type: AWS::Serverless::Application
    Properties:
      Location:
        ApplicationId: arn:aws:serverlessrepo:eu-central-1:443526418261:applications/holy-lambda-babashka-runtime
        SemanticVersion: <SEMANTIC_VERSION>")

(defn babashka-runtime-layer-template
  []
  (s/replace -babashka-runtime-layer-template #"<SEMANTIC_VERSION>" RUNTIME_VERSION))

(def tasks-deps-edn
  {:mvn/local-repo ".holy-lambda/.m2"
   :aliases {:deps {:replace-deps {'org.clojure/tools.deps.alpha {:mvn/version "0.11.910"}
                                   'org.slf4j/slf4j-nop {:mvn/version "1.7.25"}}
                    :ns-default 'clojure.tools.cli.api}
             :test {:extra-paths ["test"]}

             :uberjar {:replace-deps {'com.github.seancorfield/depstar {:mvn/version "2.0.216"}}
                       :exec-fn 'hf.depstar/uberjar
                       :exec-args {:aot true}}

             ;; build a jar (library):
             :jar {:replace-deps {'com.github.seancorfield/depstar {:mvn/version "2.0.216"}}
                   :exec-fn 'hf.depstar/jar
                   :exec-args {}}
             ;; generic depstar alias, use with jar or uberjar function name:
             :depstar {:replace-deps {'com.github.seancorfield/depstar {:mvn/version "2.0.216"}}
                       :ns-default 'hf.depstar
                       :exec-args {}}}})

(defn deps-sync--deps
  []
  (stat-file "deps.edn")
  (hpr "Syncing project and holy-lambda" (accent "deps.edn"))
  (docker:run "deps -A:depstar -P && deps -P")
  (deps-sync--babashka))

(defn cloudformation-description
  [& [silent?]]
  (let [cloudformation-string (shs "aws" "cloudformation" "describe-stacks")
        cloudformation (if (s/blank? cloudformation-string)
                         (if silent?
                           nil
                           (do (hpr (pre "Unable to get information about stacks. Use AWS UI to get proper ARN for layer"))
                               (hpr "Choose one ARN and put it at template.yml Fn:Layers")
                               (System/exit 1)))
                         (try
                           (json/parse-string cloudformation-string true)
                           (catch Exception err
                             (if silent?
                               nil
                               (do
                                 (hpr (pre "Unable to parse information about stacks."))
                                 (println err)
                                 (System/exit 1))))))]
    cloudformation))

(defn babashka-runtime-layer-ARN
  []
  (when-let [mstacks (seq (filterv (fn [stack] (s/includes? (:StackName stack) "holy-lambda-bbrl-instance-HolyLambdaBabashkaRuntime")) (:Stacks (cloudformation-description))))]
    (let [ARN (some-> mstacks
                      first
                      :Outputs
                      first
                      :OutputValue)]
      (when-not (s/blank? ARN)
        (s/trim ARN)))))

(defn runtime-sync-hook--babashka
  []
  (if-let [ARN (babashka-runtime-layer-ARN)]
    (hpr "Babashka runtime layer exists. Your layer ARN is:" (accent ARN) "(deployment skipped)")
    (do
      (hpr "Babashka runtime needs a special layer for both local invocations and deployments published here: https://serverlessrepo.aws.amazon.com/applications/eu-central-1/443526418261/holy-lambda-babashka-runtime.")
      (if-not DEPLOY_DEPENDANTS?
        (hpr "Dependants auto deployment is not allowed. Manually deployed provided above application and reference layer ARN in template.yml Function Layers section")
        (let [stack-name "holy-lambda-bbrl-instance"]
          (hpr "Trying to deploy an babashka runtime layer, since" (accent ":auto-deploy-dependants?") "is set to" (accent "true"))

          (io/make-parents BABASHKA_RUNTIME_LAYER_FILE)
          (spit BABASHKA_RUNTIME_LAYER_FILE (babashka-runtime-layer-template))

          (-create-bucket (str stack-name (hash BUCKET_NAME)))
          (apply shell
                 "sam deploy"
                 "--template-file"  BABASHKA_RUNTIME_LAYER_FILE
                 "--stack-name"     stack-name
                 "--s3-bucket"      (str stack-name (hash BUCKET_NAME))
                 "--no-confirm-changeset"
                 "--capabilities"   ["CAPABILITY_IAM" "CAPABILITY_AUTO_EXPAND"])

          (hpr "Waiting 5 seconds for deployment to propagate...")
          (Thread/sleep 5000)
          (hpr "Checking the ARN of published layer. This might take a while..")
          (hpr (prs "Your ARN for babashka runtime layer is:") (accent (babashka-runtime-layer-ARN)))))))
  )

(defn runtime-sync-hook
  []
  (case RUNTIME_NAME
    :babashka (runtime-sync-hook--babashka)
    nil))

(defn stack:sync
  "     \033[0;31m>\033[0m Syncs project & dependencies from either:
       \t\t        - \033[0;31m<Clojure>\033[0m  project.clj
       \t\t        - \033[0;31m<Clojure>\033[0m  deps.edn
       \t\t        - \033[0;31m<Babashka>\033[0m bb.edn:runtime:pods"
  []
  (when-not (fs/exists? (io/file ".holy-lambda"))
    (hpr "Directory" (accent ".holy-lambda") "does not exists. Syncing with docker image!")
    (let [cid (gensym "holy-lambda")]
      (shs "docker" "create" "--user" USER_GID "-ti" "--name" (str cid)  IMAGE_CORDS "bash")
      (shs "docker" "cp" (str cid ":/project/.holy-lambda") ".holy-lambda")
      (shs "docker" "rm" "-f" (str cid))))

  (when-not (fs/exists? (io/file ".holy-lambda"))
    (hpr (pre "Unable to sync docker image content with") (accent ".holy-lambda") (pre "project directory!")))

  ;; Correct holy-lambda deps.edn
  (spit HOLY_LAMBDA_DEPS_PATH (edn->pp-sedn tasks-deps-edn))

  ;; Sync
  (deps-sync--deps)

  ;; Runtime postprocess hook
  (runtime-sync-hook)

  (hpr "Sync completed!"))

(defn stack-files-check--java
  []
  (when-not (fs/exists? (io/file OUTPUT_JAR_PATH))
    (hpr (pre "No") (accent OUTPUT_JAR_PATH) (pre "found! Run") (accent "stack:compile"))
    (System/exit 1)))

(defn stack-files-check
  []
  (when-not (fs/exists? (io/file ".holy-lambda"))
    (hpr (pre "No") (accent ".holy-lambda") (pre "directory! Run") (accent "stack:sync"))
    (System/exit 1))

  (case RUNTIME_NAME
    :java (stack-files-check--java)
    :babashka nil
    )
  )

(defn build-stale?
  []
  (and
   (not= RUNTIME_NAME :babashka)
   (boolean (seq (fs/modified-since OUTPUT_JAR_PATH (fs/glob "src" "**/**.{clj,cljc,cljs}"))))))

(defn stack:api
  "     \033[0;31m>\033[0m Runs local api (check sam local start-api):
       \t\t        - \033[0;31m:debug\033[0m  - run api in \033[0;31mdebug mode\033[0m"
  [& args]
  (let [{:keys [debug]} (norm-args args)]
    (stack-files-check)
    (when (build-stale?)
      (hpr (prw "Build is stale. Consider recompilation via") (accent "stack:compile")))

    (shell (str "sam local start-api"
                " --parameter-overrides " (parameters)
                " --template " TEMPLATE_FILE
                (when debug " --debug")) )))

(defn modify-template
  []
  (let [buffer (slurp TEMPLATE_FILE)
        {:strs [CodeUri Runtime]} (-parameters :relative)]

    (when-not (re-find #"<HOLY_LAMBDA_CODE_URI>" buffer)
      (hpr (pre "<HOLY_LAMBDA_CODE_URI> definition should be available. Check related issue https://github.com/aws/aws-sam-cli/issues/2835"))
      (System/exit 1))

    (when-not (re-find #"<HOLY_LAMBDA_RUNTIME>" buffer)
      (hpr (pre "<HOLY_LAMBDA_RUNTIME> definition should be available. Check related issue https://github.com/aws/aws-sam-cli/issues/2835"))
      (System/exit 1))

    (spit MODIFIED_TEMPLATE_FILE
          (-> buffer
              (s/replace #"<HOLY_LAMBDA_CODE_URI>" CodeUri)
              (s/replace #"<HOLY_LAMBDA_RUNTIME>" Runtime)
              (s/replace #"\!Ref CodeUri" CodeUri)))))

(defn bucket:create
  "     \033[0;31m>\033[0m Creates a s3 bucket using \033[0;31m:bucket-name\033[0m"
  []
  (if (bucket-exists?)
    (do
      (hpr (prs "Bucket") (accent BUCKET_NAME) "already exists!")
      (hpr (prw "Sometimes bucket is not immediately appear to be removed and is still listed. In such case change") (str (accent ":infra:bucket-name") "!")))
    (do (hpr (prs "Creating a bucket") (accent BUCKET_NAME))
        (-create-bucket))))

(defn check-n-create-bucket
  []
  (when-not (bucket-exists?)
    (hpr (prw "Bucket") (accent BUCKET_NAME) "does not exists. Creating one!")
    (bucket:create)))

(defn stack:pack
  "     \033[0;31m>\033[0m Packs \033[0;31mCloudformation\033[0m stack"
  []
  (stack-files-check)
  ;; Check https://github.com/aws/aws-sam-cli/issues/2835
  ;; https://github.com/aws/aws-sam-cli/issues/2836
  (check-n-create-bucket)
  (modify-template)
  (shell "sam" "package"
         "--template-file" MODIFIED_TEMPLATE_FILE
         "--output-template-file" PACKAGED_TEMPLATE_FILE
         "--s3-bucket" BUCKET_NAME
         "--s3-prefix" BUCKET_PREFIX
         "--region" REGION))

(defn bucket:remove
  "     \033[0;31m>\033[0m Removes a s3 bucket using \033[0;31m:bucket-name\033[0m"
  []
  (if-not (bucket-exists?)
    (hpr (pre "Bucket") (accent BUCKET_NAME) " does not exists! Not removing")
    (do (hpr (prs "Removing a bucket") (accent BUCKET_NAME))
        (-remove-bucket))))

(defn stack:deploy
  "     \033[0;31m>\033[0m Deploys \033[0;31mCloudformation\033[0m stack
  \t\t        - \033[0;31m:guided\033[0m - guide the deployment
  \t\t        - \033[0;31m:dry\033[0m    - execute changeset?"
  [& args]
  (let [{:keys [guided dry]} (norm-args args)]
    (if-not (fs/exists? (io/file PACKAGED_TEMPLATE_FILE))
      (hpr (pre "No") (accent PACKAGED_TEMPLATE_FILE) (pre "found. Run") (accent "stack:pack"))
      (do
        (check-n-create-bucket)
        (apply shell "sam" "deploy"
               "--template-file" PACKAGED_TEMPLATE_FILE
               "--stack-name" STACK_NAME
               "--region" REGION
               (when dry "--no-execute-changeset")
               (when guided "--guided")
               "--parameter-overrides" (parameters)
               (when CAPABILITIES "--capabilities") CAPABILITIES)))))

(defn stack:compile
  "     \033[0;31m>\033[0m Compiles sources if necessary"
  []
  (when (= RUNTIME_NAME :babashka)
    (hpr "Nothing to compile. Sources are provided as is to" (accent "babashka") "runtime")
    (System/exit 0))

    (when-not (build-stale?)
      (hpr "Nothing to compile. Sources did not change!")
      (System/exit 0))

  (docker:run (str "clojure -X:uberjar :aot true :jar " OUTPUT_JAR_PATH " :main-class " (str ENTRYPOINT))))

(defn stack:invoke
  "     \033[0;31m>\033[0m Invokes lambda fn (check sam local invoke --help):
       \t\t        - \033[0;31m:name\033[0m   - either \033[0;31m:name\033[0m or \033[0;31m:stack:default-lambda\033[0m
       \t\t        - \033[0;31m:event\033[0m  - path to \033[0;31mevent file\033[0m
       \t\t        - \033[0;31m:envs\033[0m   - path to \033[0;31menvs file\033[0m
       \t\t        - \033[0;31m:logs\033[0m   - logfile to runtime logs to"
  [& args]
  (stack-files-check)
  (when (build-stale?)
    (hpr (prw "Build is stale. Consider recompilation via") (accent "stack:compile")))
  (let [{:keys [name event-file envs-file logs]} (norm-args args)]
    (shell "sam" "local" "invoke" (or name DEFAULT_LAMBDA_NAME)
           "--parameter-overrides" (parameters)
           (when logs "-l") logs
           (when event-file "-e") event-file
           (when envs-file "-n") envs-file)))

(defn mvn-local-test
  [file]
  (if (not= (:mvn/local-repo (edn/read-string (slurp (io/file file)))) ".holy-lambda/.m2")
    (hpr (pre "property") (accent ":mvn/local-repo") (pre "in file") (accent file) (pre "should be set to") (accent ".holy-lambda/.m2"))
    (hpr (prs "property") (accent ":mvn/local-repo") (prs "in file") (accent file) (prs "is correct"))))

(defn stack:doctor
  "     \033[0;31m>\033[0m Diagnoses common issues of holy-lambda stack"
  []
  (println "")
  (hpr "---------------------------------------")
  (hpr " Checking health of holy-lambda stack")
  (hpr " Home directory is:       " (accent HOME_DIR))
  (hpr " AWS directory is:        " (accent AWS_DIR))
  (hpr " Babashka tasks version:  " (accent TASKS_VERSION))
  (hpr " Babashka version:        " (accent (s/trim (shs "bb" "version"))))
  (hpr " Runtime:                 " (accent RUNTIME_NAME))
  (hpr " Runtime entrypoint:      " (accent ENTRYPOINT))
  (hpr " Stack name:              " (accent STACK_NAME))
  (hpr " S3 Bucket name:          " (accent BUCKET_NAME))
  (hpr "---------------------------------------\n")

  (when-not (fs/exists? (io/file AWS_DIR))
    (hpr (pre "$HOME/.aws does not exists. Did you run") (accent "aws configure")))

  (read-string (slurp (io/file "deps.edn")))

  (if-not (contains? AVAILABLE_RUNTIMES RUNTIME_NAME)
    (do
      (hpr (str (pre ":runtime ") (accent RUNTIME_NAME) (pre " is not supported!")))
      (hpr (str "Choose one of supported build tools: " AVAILABLE_RUNTIMES)))
    (hpr (prs ":runtime looks good")))

  (if-not ENTRYPOINT
    (hpr (pre ":runtime:entrypoint is required!"))
    (hpr (prs ":runtime:entrypoint looks good")))

  (if-not CAPABILITIES
    (hpr (pre ":stack:capabilities is required!"))
    (hpr (prs ":stack:capabilities looks good")))

  (if-not STACK_NAME
    (hpr (pre ":stack:name is required!"))
    (hpr (prs ":stack:name looks good")))

  (mvn-local-test "deps.edn")
  (mvn-local-test "bb.edn")

  (if (fs/exists? (io/file HOLY_LAMBDA_DEPS_PATH))
    (hpr (prs "Syncing stack is not required"))
    (hpr (pre "Stack is not synced! Run:") (accent "stack:sync")))

  (if-not (contains? AVAILABLE_REGIONS REGION)
    (do
      (hpr (str (pre "Region ") (accent REGION) (pre " is not supported!")))
      (hpr (str "Choose one of supported regions:\n" (with-out-str (pprint/pprint AVAILABLE_REGIONS)))))
    (hpr (prs ":infra:region definition looks good")))

  (if (s/includes? BUCKET_PREFIX "_")
    (hpr (pre ":infra:bucket-prefix should not contain any of _ characters"))
    (hpr (prs ":infra:bucket-prefix looks good")))

  (if-not TEMPLATE_FILE
    (hpr (pre ":stack:template is required!"))
    (hpr (prs ":stack:template looks good")))

  (if (s/includes? BUCKET_NAME "_")
    (hpr (pre ":infra:bucket-name should not contain any of _ characters"))
    (hpr (str (prs ":infra:bucket-name looks good")
         (when-not (bucket-exists?)
           (str (prs ", but ") (accent BUCKET_NAME) (prw " does not exists (use bb :bucket:create)"))))))

  (if-let [cmds-not-found (seq (filter (comp not command-exists?) REQUIRED_COMMANDS))]
    (hpr (str (pre (str "Commands " cmds-not-found " not found. Install all then run: ")) (underline "bb doctor")))
    (do
      (hpr (prs "Required commands") (accent (str REQUIRED_COMMANDS)) (prs "installed!"))
      (println)
      (stat-file TEMPLATE_FILE)
      (hpr "Validating" (accent TEMPLATE_FILE))
      (shell "sam validate"))))

(defn stack:logs
  "     \033[0;31m>\033[0m Possible arguments (check sam logs --help):
       \t\t        - \033[0;31m:name\033[0m   - either \033[0;31m:name\033[0m or \033[0;31m:stack:default-lambda\033[0m
       \t\t        - \033[0;31m:e\033[0m      - fetch logs up to this time
       \t\t        - \033[0;31m:s\033[0m      - fetch logs starting at this time
       \t\t        - \033[0;31m:filter\033[0m - find logs that match terms "
  [& args]
  (let [{:keys [name tail s e filter]} (norm-args args)]
    (shell "sam" "logs"
           "-n" (or name DEFAULT_LAMBDA_NAME)
           (when s "-s") (when s s)
           (when e "-e") (when e e)
           (when filter "--filter") (when filter filter)
           (when tail "-t"))))

(defn local-tasks-match-remote?
  []
  (= (s/replace
      (second (re-find
               TASKS_VERSION_MATCH
               (:body (curl/get REMOTE_TASKS))))
      "\""
      "")
     TASKS_VERSION))

(defn stack:version
  "     \033[0;31m>\033[0m Outputs holy-lambda babashka tasks version"
  []
  (hpr (str (prs "Current tasks version is: ") (accent TASKS_VERSION)))
  (when-not (local-tasks-match-remote?)
    (hpr "There is newer version of tasks on remote. Please update tasks :sha")))

(defn stack:purge
  "     \033[0;31m>\033[0m Purges build artifacts"
  []
  (let [artifacts [".aws"
                   ".holy-lambda"
                   "Dockerfile.ee"
                   "node_modules"]]

    (hpr  (str (accent "Purging build artifacts:") "\n\n" (plist artifacts)))

    (doseq [art artifacts]
      (shell (str "rm -rf " art)))

    (hpr  (prs "Build artifacts purged"))))

(defn docker:build:ee
  "     \033[0;31m>\033[0m Builds local image for GraalVM EE "
  []
  (hpr  (accent "Building GraalVM EE docker image"))
  (spit "Dockerfile.ee" (:body (curl/get "https://raw.githubusercontent.com/FieryCod/holy-lambda/master/docker/ee/Dockerfile")))
  (shell "docker build . -f Dockerfile.ee -t fierycod/graalvm-native-image:ee")
  (shell "rm -rf Dockerfile.ee"))

(defn stack:lint
  "     \033[0;31m>\033[0m Lints the project"
  []
  (shell "clj-kondo --lint src:test"))

(defn stack:deploy:full
  "     \033[0;31m>\033[0m Shortcut for running [stack:sync, stack:compile, :stack:pack, :stack:deploy]"
  []
  (stack:sync)
  (when-not (= :babashka RUNTIME_NAME)
    (stack:compile))
  (stack:pack)
  (stack:deploy))

(defn stack:destroy
  "     \033[0;31m>\033[0m Destroys \033[0;31mCloudformation\033[0m stack & removes bucket"
  []
  (shell "aws" "cloudformation" "delete-stack"
         "--stack-name" STACK_NAME
         "--region" REGION)
  (bucket:remove))

(defn stack:describe
  "     \033[0;31m>\033[0m Describes \033[0;31mCloudformation\033[0m stack"
  []
  (shell "aws" "cloudformation" "describe-stacks"
         "--stack-name" STACK_NAME))