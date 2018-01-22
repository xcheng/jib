/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.crepecake.builder;

import com.google.cloud.tools.crepecake.Timer;
import com.google.cloud.tools.crepecake.cache.Cache;
import com.google.cloud.tools.crepecake.cache.CacheMetadataCorruptedException;
import com.google.cloud.tools.crepecake.cache.CachedLayer;
import com.google.cloud.tools.crepecake.http.Authorization;
import com.google.cloud.tools.crepecake.image.DuplicateLayerException;
import com.google.cloud.tools.crepecake.image.Image;
import com.google.cloud.tools.crepecake.image.ImageLayers;
import com.google.cloud.tools.crepecake.image.LayerCountMismatchException;
import com.google.cloud.tools.crepecake.image.LayerPropertyNotFoundException;
import com.google.cloud.tools.crepecake.registry.NonexistentDockerCredentialHelperException;
import com.google.cloud.tools.crepecake.registry.NonexistentServerUrlDockerCredentialHelperException;
import com.google.cloud.tools.crepecake.registry.RegistryAuthenticationFailedException;
import com.google.cloud.tools.crepecake.registry.RegistryException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** All the steps to build an image. */
public class BuildImageSteps {

  private final BuildConfiguration buildConfiguration;
  private final SourceFilesConfiguration sourceFilesConfiguration;
  private final Path cacheDirectory;

  public BuildImageSteps(
      BuildConfiguration buildConfiguration,
      SourceFilesConfiguration sourceFilesConfiguration,
      Path cacheDirectory) {
    this.buildConfiguration = buildConfiguration;
    this.sourceFilesConfiguration = sourceFilesConfiguration;
    this.cacheDirectory = cacheDirectory;
  }

  public void run()
      throws CacheMetadataCorruptedException, IOException, RegistryAuthenticationFailedException,
          RegistryException, DuplicateLayerException, LayerCountMismatchException,
          LayerPropertyNotFoundException, NonexistentServerUrlDockerCredentialHelperException,
          NonexistentDockerCredentialHelperException {
    try (Timer t = Timer.push("BuildImageSteps")) {

      try (Cache cache = Cache.init(cacheDirectory)) {
        try (Timer t2 = Timer.push("AuthenticatePullStep")) {
          // Authenticates base image pull.
          AuthenticatePullStep authenticatePullStep = new AuthenticatePullStep(buildConfiguration);
          Authorization pullAuthorization = authenticatePullStep.run(null);

          Timer.time("PullBaseImageStep");
          // Pulls the base image.
          PullBaseImageStep pullBaseImageStep =
              new PullBaseImageStep(buildConfiguration, pullAuthorization);
          Image baseImage = pullBaseImageStep.run(null);

          Timer.time("PullAndCacheBaseImageLayersStep");
          // Pulls and caches the base image layers.
          PullAndCacheBaseImageLayersStep pullAndCacheBaseImageLayersStep =
              new PullAndCacheBaseImageLayersStep(buildConfiguration, cache, pullAuthorization);
          ImageLayers<CachedLayer> baseImageLayers = pullAndCacheBaseImageLayersStep.run(baseImage);

          Timer.time("AuthenticatePushStep");
          // Authenticates push.
          AuthenticatePushStep authenticatePushStep = new AuthenticatePushStep(buildConfiguration);
          Authorization pushAuthorization = authenticatePushStep.run(null);

          Timer.time("PushBaseImageLayersStep");
          // Pushes the base image layers.
          PushBaseImageLayersStep pushBaseImageLayersStep =
              new PushBaseImageLayersStep(buildConfiguration, pushAuthorization);
          pushBaseImageLayersStep.run(baseImageLayers);

          Timer.time("BuildAndCacheApplicationLayersStep");
          BuildAndCacheApplicationLayersStep buildAndCacheApplicationLayersStep =
              new BuildAndCacheApplicationLayersStep(sourceFilesConfiguration, cache);
          ImageLayers<CachedLayer> applicationLayers = buildAndCacheApplicationLayersStep.run(null);

          Timer.time("PushApplicationLayerStep");
          // Pushes the application layers.
          PushApplicationLayersStep pushApplicationLayersStep =
              new PushApplicationLayersStep(buildConfiguration, pushAuthorization);
          pushApplicationLayersStep.run(applicationLayers);

          Timer.time("PushImageStep");
          // Pushes the new image manifest.
          Image image =
              new Image()
                  .addLayers(baseImageLayers)
                  .addLayers(applicationLayers)
                  .setEntrypoint(getEntrypoint());
          PushImageStep pushImageStep = new PushImageStep(buildConfiguration, pushAuthorization);
          pushImageStep.run(image);

          System.out.println(getEntrypoint());
        }
      }
    } finally {
      Timer.print();
    }
  }

  private List<String> getEntrypoint() {
    List<String> classPaths = new ArrayList<>();
    classPaths.add(
        sourceFilesConfiguration.getDependenciesExtractionPath().resolve("*").toString());
    classPaths.add(sourceFilesConfiguration.getResourcesExtractionPath().toString());
    classPaths.add(sourceFilesConfiguration.getClassesExtractionPath().toString());

    String entrypoint = String.join(":", classPaths);

    return Arrays.asList("java", "-cp", entrypoint, buildConfiguration.getMainClass());
  }
}
