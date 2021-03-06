/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.Set;

public class AppleLibraryDescription implements
    Description<AppleLibraryDescription.Arg>,
    Flavored,
    MetadataProvidingDescription<AppleLibraryDescription.Arg> {
  public static final BuildRuleType TYPE = BuildRuleType.of("apple_library");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      CxxCompilationDatabase.COMPILATION_DATABASE,
      CxxCompilationDatabase.UBER_COMPILATION_DATABASE,
      CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
      CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
      CxxDescriptionEnhancer.STATIC_FLAVOR,
      CxxDescriptionEnhancer.SHARED_FLAVOR,
      AppleDescriptions.FRAMEWORK_FLAVOR,
      AppleDebugFormat.DWARF_AND_DSYM.getFlavor(),
      AppleDebugFormat.NONE.getFlavor(),
      ImmutableFlavor.of("default"));

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private enum Type implements FlavorConvertible {
    HEADERS(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    MACH_O_BUNDLE(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR),
    FRAMEWORK(AppleDescriptions.FRAMEWORK_FLAVOR),
    ;

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  public static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("C/C++ Library Type", Type.class);

  private final CxxLibraryDescription delegate;
  private final ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;

  public AppleLibraryDescription(
      CxxLibraryDescription delegate,
      ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore) {
    this.delegate = delegate;
    this.platformFlavorsToAppleCxxPlatforms = platformFlavorsToAppleCxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleLibraryDescription.Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR) ||
        delegate.hasFlavors(flavors);
  }

  @Override
  public <A extends AppleLibraryDescription.Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(
        params.getBuildTarget());
    if (type.isPresent() && type.get().getValue().equals(Type.FRAMEWORK)) {
      if (!args.infoPlist.isPresent()) {
        throw new HumanReadableException(
            "Cannot create framework for apple_library '%s':\n" +
            "No value specified for 'info_plist' attribute.",
            params.getBuildTarget().getUnflavoredBuildTarget());
      }
      if (!AppleDescriptions.INCLUDE_FRAMEWORKS.getValue(params.getBuildTarget()).isPresent()) {
        return resolver.requireRule(
            params.getBuildTarget().withAppendedFlavor(
                AppleDescriptions.INCLUDE_FRAMEWORKS_FLAVOR));
      }

      return AppleDescriptions.createAppleBundle(
          delegate.getCxxPlatforms(),
          defaultCxxPlatform,
          platformFlavorsToAppleCxxPlatforms,
          targetGraph,
          params,
          resolver,
          codeSignIdentityStore,
          provisioningProfileStore,
          params.getBuildTarget(),
          Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK),
          Optional.<String>absent(),
          args.infoPlist.get(),
          args.infoPlistSubstitutions,
          args.deps.get(),
          args.getTests());
    }

    return createBuildRule(
        params,
        resolver,
        args,
        args.linkStyle,
        Optional.<SourcePath>absent(),
        ImmutableSet.<BuildTarget>of());
  }

  public <A extends AppleNativeTargetDescriptionArg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args,
      Optional<Linker.LinkableDepType> linkableDepType,
      Optional<SourcePath> bundleLoader,
      ImmutableSet<BuildTarget> blacklist) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    CxxLibraryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
    AppleDescriptions.populateCxxLibraryDescriptionArg(
        pathResolver,
        delegateArg,
        args,
        params.getBuildTarget());

    return delegate.createBuildRule(
        params,
        resolver,
        delegateArg,
        linkableDepType,
        bundleLoader,
        blacklist);
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    if (!metadataClass.isAssignableFrom(FrameworkDependencies.class) ||
        !buildTarget.getFlavors().contains(AppleDescriptions.FRAMEWORK_FLAVOR)) {
      CxxLibraryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
      AppleDescriptions.populateCxxLibraryDescriptionArg(
          new SourcePathResolver(resolver),
          delegateArg,
          args,
          buildTarget);
      return delegate.createMetadata(buildTarget, resolver, delegateArg, metadataClass);
    }
    Optional<Flavor> cxxPlatformFlavor = delegate.getCxxPlatforms().getFlavor(buildTarget);
    Preconditions.checkState(
        cxxPlatformFlavor.isPresent(),
        "Could not find cxx platform in:\n%s",
        Joiner.on(", ").join(buildTarget.getFlavors()));
    ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();
    for (BuildTarget dep : args.deps.get()) {
      Optional<FrameworkDependencies> frameworks =
          resolver.requireMetadata(
              BuildTarget.builder(dep)
                  .addFlavors(AppleDescriptions.FRAMEWORK_FLAVOR)
                  .addFlavors(AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR)
                  .addFlavors(cxxPlatformFlavor.get())
                  .build(),
              FrameworkDependencies.class);
      if (frameworks.isPresent()) {
        sourcePaths.addAll(frameworks.get().getSourcePaths());
      }
    }
    // Not all parts of Buck use require yet, so require the rule here so it's available in the
    // resolver for the parts that don't.
    resolver.requireRule(buildTarget);
    sourcePaths.add(new BuildTargetSourcePath(buildTarget));
    return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths.build())));
  }

  public static boolean isSharedLibraryTarget(BuildTarget target) {
    return target.getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg {
    public Optional<SourcePath> infoPlist;
    public Optional<ImmutableMap<String, String>> infoPlistSubstitutions;
  }

}
