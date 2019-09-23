// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License
package com.google.devtools.build.lib.rules.android;

import static com.google.devtools.build.lib.analysis.config.CompilationMode.OPT;

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Whitelist;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.RuleErrorConsumer;
import com.google.devtools.build.lib.packages.TriState;
import com.google.devtools.build.lib.skylarkbuildapi.android.AndroidDataContextApi;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * Wraps common tools and settings used for working with Android assets, resources, and manifests.
 *
 * <p>Do not create implementation classes directly - instead, get the appropriate one from {@link
 * com.google.devtools.build.lib.rules.android.AndroidSemantics}.
 *
 * <p>The {@link Label}, {@link ActionConstructionContext}, and BusyBox {@link FilesToRunProvider}
 * are needed to create virtually all actions for working with Android data, so it makes sense to
 * bundle them together. Additionally, this class includes some common tools (such as an SDK) that
 * are used in BusyBox actions.
 */
public class AndroidDataContext implements AndroidDataContextApi {

  // Feature which would cause AndroidCompiledResourceMerger actions to pass a flag with the same
  // name to ResourceProcessorBusyBox.
  private static final String ANNOTATE_R_FIELDS_FROM_TRANSITIVE_DEPS =
      "annotate_r_fields_from_transitive_deps";

  // If specified, omit resources from transitive dependencies when generating Android R classes.
  private static final String OMIT_TRANSITIVE_RESOURCES_FROM_ANDROID_R_CLASSES =
      "android_resources_strict_deps";

  private final RuleContext ruleContext;
  private final FilesToRunProvider busybox;
  private final AndroidSdkProvider sdk;
  private final boolean persistentBusyboxToolsEnabled;
  private final boolean compatibleForResourcePathShortening;
  private final boolean compatibleForResourceNameObfuscation;
  private final boolean throwOnProguardApplyDictionary;
  private final boolean throwOnProguardApplyMapping;
  private final boolean throwOnResourceConflict;
  private final boolean useDataBindingV2;

  public static AndroidDataContext forNative(RuleContext ruleContext) {
    return makeContext(ruleContext);
  }

  public static AndroidDataContext makeContext(RuleContext ruleContext) {
    AndroidConfiguration androidConfig =
        ruleContext.getConfiguration().getFragment(AndroidConfiguration.class);

    return new AndroidDataContext(
        ruleContext,
        ruleContext.getExecutablePrerequisite("$android_resources_busybox", Mode.HOST),
        androidConfig.persistentBusyboxTools(),
        AndroidSdkProvider.fromRuleContext(ruleContext),
        lacksAllowlistExemptions(ruleContext, "allow_raw_access_to_resource_paths", true),
        lacksAllowlistExemptions(ruleContext, "allow_resource_name_obfuscation_opt_out", true),
        lacksAllowlistExemptions(ruleContext, "allow_proguard_apply_dictionary", false),
        lacksAllowlistExemptions(ruleContext, "allow_proguard_apply_mapping", false),
        lacksAllowlistExemptions(ruleContext, "allow_resource_conflicts", false),
        androidConfig.useDataBindingV2());
  }

  private static boolean lacksAllowlistExemptions(
      RuleContext ruleContext, String whitelistName, boolean valueIfNoWhitelist) {
    return Whitelist.hasWhitelist(ruleContext, whitelistName)
        ? !Whitelist.isAvailable(ruleContext, whitelistName)
        : valueIfNoWhitelist;
  }

  protected AndroidDataContext(
      RuleContext ruleContext,
      FilesToRunProvider busybox,
      boolean persistentBusyboxToolsEnabled,
      AndroidSdkProvider sdk,
      boolean compatibleForResourcePathShortening,
      boolean compatibleForResourceNameObfuscation,
      boolean throwOnProguardApplyDictionary,
      boolean throwOnProguardApplyMapping,
      boolean throwOnResourceConflict,
      boolean useDataBindingV2) {
    this.persistentBusyboxToolsEnabled = persistentBusyboxToolsEnabled;
    this.ruleContext = ruleContext;
    this.busybox = busybox;
    this.sdk = sdk;
    this.compatibleForResourcePathShortening = compatibleForResourcePathShortening;
    this.compatibleForResourceNameObfuscation = compatibleForResourceNameObfuscation;
    this.throwOnProguardApplyDictionary = throwOnProguardApplyDictionary;
    this.throwOnProguardApplyMapping = throwOnProguardApplyMapping;
    this.throwOnResourceConflict = throwOnResourceConflict;
    this.useDataBindingV2 = useDataBindingV2;
  }

  public Label getLabel() {
    return ruleContext.getLabel();
  }

  public ActionConstructionContext getActionConstructionContext() {
    return ruleContext;
  }

  public RuleErrorConsumer getRuleErrorConsumer() {
    return ruleContext;
  }

  public FilesToRunProvider getBusybox() {
    return busybox;
  }

  public AndroidSdkProvider getSdk() {
    return sdk;
  }

  /*
   * Convenience methods. These are just slightly cleaner ways of doing common tasks.
   */

  /** Builds and registers a {@link SpawnAction.Builder}. */
  public void registerAction(SpawnAction.Builder spawnActionBuilder) {
    registerAction(spawnActionBuilder.build(ruleContext));
  }

  /** Registers one or more actions. */
  public void registerAction(ActionAnalysisMetadata... actions) {
    ruleContext.registerAction(actions);
  }

  public Artifact createOutputArtifact(SafeImplicitOutputsFunction function)
      throws InterruptedException {
    return ruleContext.getImplicitOutputArtifact(function);
  }

  public Artifact getUniqueDirectoryArtifact(String uniqueDirectorySuffix, String relative) {
    return ruleContext.getUniqueDirectoryArtifact(uniqueDirectorySuffix, relative);
  }

  public Artifact getUniqueDirectoryArtifact(String uniqueDirectorySuffix, PathFragment relative) {
    return ruleContext.getUniqueDirectoryArtifact(uniqueDirectorySuffix, relative);
  }

  public PathFragment getUniqueDirectory(PathFragment fragment) {
    return ruleContext.getUniqueDirectory(fragment);
  }

  public ArtifactRoot getBinOrGenfilesDirectory() {
    return ruleContext.getBinOrGenfilesDirectory();
  }

  public PathFragment getPackageDirectory() {
    return ruleContext.getPackageDirectory();
  }

  public AndroidConfiguration getAndroidConfig() {
    return ruleContext.getConfiguration().getFragment(AndroidConfiguration.class);
  }

  /** Indicates whether Busybox actions should be passed the "--debug" flag */
  public boolean useDebug() {
    return getActionConstructionContext().getConfiguration().getCompilationMode() != OPT;
  }

  public boolean isPersistentBusyboxToolsEnabled() {
    return persistentBusyboxToolsEnabled;
  }

  public boolean compatibleForResourcePathShortening() {
    return compatibleForResourcePathShortening;
  }

  public boolean compatibleForResourceNameObfuscation() {
    return compatibleForResourceNameObfuscation;
  }

  public boolean throwOnProguardApplyDictionary() {
    return throwOnProguardApplyDictionary;
  }

  public boolean throwOnProguardApplyMapping() {
    return throwOnProguardApplyMapping;
  }

  public boolean throwOnResourceConflict() {
    return throwOnResourceConflict;
  }

  public boolean useDataBindingV2() {
    return useDataBindingV2;
  }

  public boolean annotateRFieldsFromTransitiveDeps() {
    return ruleContext.getFeatures().contains(ANNOTATE_R_FIELDS_FROM_TRANSITIVE_DEPS);
  }

  boolean omitTransitiveResourcesFromAndroidRClasses() {
    return ruleContext.getFeatures().contains(OMIT_TRANSITIVE_RESOURCES_FROM_ANDROID_R_CLASSES);
  }

  boolean useResourceShrinking() {
    TriState state = ruleContext.attributes().get("shrink_resources", BuildType.TRISTATE);
    if (state == TriState.AUTO) {
      state = getAndroidConfig().useAndroidResourceShrinking() ? TriState.YES : TriState.NO;
    }

    return state == TriState.YES;
  }

  boolean useResourcePathShortening() {
    // Use resource path shortening iff:
    //   1) --experimental_android_resource_path_shortening
    //   2) -c opt
    //   3) Not on allowlist exempting from compatibleForResourcePathShortening
    return getAndroidConfig().useAndroidResourcePathShortening()
        && getActionConstructionContext().getConfiguration().getCompilationMode() == OPT
        && compatibleForResourcePathShortening;
  }

  boolean useResourceNameObfuscation() {
    // Use resource name obfuscation iff:
    //   1) --experimental_android_resource_name_obfuscation
    //   2) -c opt
    //   3) resource shrinking is on
    //   4) Not on allowlist exempting from compatibleForResourceNameObfuscation
    return getAndroidConfig().useAndroidResourceNameObfuscation()
        && getActionConstructionContext().getConfiguration().getCompilationMode() == OPT
        && useResourceShrinking()
        && compatibleForResourceNameObfuscation;
  }
}
