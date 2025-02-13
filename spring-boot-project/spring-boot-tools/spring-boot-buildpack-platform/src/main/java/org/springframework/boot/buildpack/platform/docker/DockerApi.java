/*
 * Copyright 2012-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.buildpack.platform.docker;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.hc.core5.net.URIBuilder;

import org.springframework.boot.buildpack.platform.docker.configuration.DockerHost;
import org.springframework.boot.buildpack.platform.docker.transport.HttpTransport;
import org.springframework.boot.buildpack.platform.docker.transport.HttpTransport.Response;
import org.springframework.boot.buildpack.platform.docker.type.ContainerConfig;
import org.springframework.boot.buildpack.platform.docker.type.ContainerContent;
import org.springframework.boot.buildpack.platform.docker.type.ContainerReference;
import org.springframework.boot.buildpack.platform.docker.type.ContainerStatus;
import org.springframework.boot.buildpack.platform.docker.type.Image;
import org.springframework.boot.buildpack.platform.docker.type.ImageArchive;
import org.springframework.boot.buildpack.platform.docker.type.ImageArchiveManifest;
import org.springframework.boot.buildpack.platform.docker.type.ImageReference;
import org.springframework.boot.buildpack.platform.docker.type.VolumeName;
import org.springframework.boot.buildpack.platform.io.IOBiConsumer;
import org.springframework.boot.buildpack.platform.io.TarArchive;
import org.springframework.boot.buildpack.platform.json.JsonStream;
import org.springframework.boot.buildpack.platform.json.SharedObjectMapper;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * Provides access to the limited set of Docker APIs needed by pack.
 *
 * @author Phillip Webb
 * @author Scott Frederick
 * @author Rafael Ceccone
 * @author Moritz Halbritter
 * @since 2.3.0
 */
public class DockerApi {

	private static final List<String> FORCE_PARAMS = Collections.unmodifiableList(Arrays.asList("force", "1"));

	static final String API_VERSION = "v1.24";

	private final HttpTransport http;

	private final JsonStream jsonStream;

	private final ImageApi image;

	private final ContainerApi container;

	private final VolumeApi volume;

	/**
	 * Create a new {@link DockerApi} instance.
	 */
	public DockerApi() {
		this(HttpTransport.create(null));
	}

	/**
	 * Create a new {@link DockerApi} instance.
	 * @param dockerHost the Docker daemon host information
	 * @since 2.4.0
	 */
	public DockerApi(DockerHost dockerHost) {
		this(HttpTransport.create(dockerHost));
	}

	/**
	 * Create a new {@link DockerApi} instance backed by a specific {@link HttpTransport}
	 * implementation.
	 * @param http the http implementation
	 */
	DockerApi(HttpTransport http) {
		this.http = http;
		this.jsonStream = new JsonStream(SharedObjectMapper.get());
		this.image = new ImageApi();
		this.container = new ContainerApi();
		this.volume = new VolumeApi();
	}

	private HttpTransport http() {
		return this.http;
	}

	private JsonStream jsonStream() {
		return this.jsonStream;
	}

	private URI buildUrl(String path, Collection<String> params) {
		return buildUrl(path, StringUtils.toStringArray(params));
	}

	private URI buildUrl(String path, String... params) {
		try {
			URIBuilder builder = new URIBuilder("/" + API_VERSION + path);
			int param = 0;
			while (param < params.length) {
				builder.addParameter(params[param++], params[param++]);
			}
			return builder.build();
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Return the Docker API for image operations.
	 * @return the image API
	 */
	public ImageApi image() {
		return this.image;
	}

	/**
	 * Return the Docker API for container operations.
	 * @return the container API
	 */
	public ContainerApi container() {
		return this.container;
	}

	public VolumeApi volume() {
		return this.volume;
	}

	/**
	 * Docker API for image operations.
	 */
	public class ImageApi {

		ImageApi() {
		}

		/**
		 * Pull an image from a registry.
		 * @param reference the image reference to pull
		 * @param listener a pull listener to receive update events
		 * @return the {@link ImageApi pulled image} instance
		 * @throws IOException on IO error
		 */
		public Image pull(ImageReference reference, UpdateListener<PullImageUpdateEvent> listener) throws IOException {
			return pull(reference, listener, null);
		}

		/**
		 * Pull an image from a registry.
		 * @param reference the image reference to pull
		 * @param listener a pull listener to receive update events
		 * @param registryAuth registry authentication credentials
		 * @return the {@link ImageApi pulled image} instance
		 * @throws IOException on IO error
		 */
		public Image pull(ImageReference reference, UpdateListener<PullImageUpdateEvent> listener, String registryAuth)
				throws IOException {
			Assert.notNull(reference, "Reference must not be null");
			Assert.notNull(listener, "Listener must not be null");
			URI createUri = buildUrl("/images/create", "fromImage", reference.toString());
			DigestCaptureUpdateListener digestCapture = new DigestCaptureUpdateListener();
			listener.onStart();
			try {
				try (Response response = http().post(createUri, registryAuth)) {
					jsonStream().get(response.getContent(), PullImageUpdateEvent.class, (event) -> {
						digestCapture.onUpdate(event);
						listener.onUpdate(event);
					});
				}
				return inspect(reference);
			}
			finally {
				listener.onFinish();
			}
		}

		/**
		 * Push an image to a registry.
		 * @param reference the image reference to push
		 * @param listener a push listener to receive update events
		 * @param registryAuth registry authentication credentials
		 * @throws IOException on IO error
		 */
		public void push(ImageReference reference, UpdateListener<PushImageUpdateEvent> listener, String registryAuth)
				throws IOException {
			Assert.notNull(reference, "Reference must not be null");
			Assert.notNull(listener, "Listener must not be null");
			URI pushUri = buildUrl("/images/" + reference + "/push");
			ErrorCaptureUpdateListener errorListener = new ErrorCaptureUpdateListener();
			listener.onStart();
			try {
				try (Response response = http().post(pushUri, registryAuth)) {
					jsonStream().get(response.getContent(), PushImageUpdateEvent.class, (event) -> {
						errorListener.onUpdate(event);
						listener.onUpdate(event);
					});
				}
			}
			finally {
				listener.onFinish();
			}
		}

		/**
		 * Load an {@link ImageArchive} into Docker.
		 * @param archive the archive to load
		 * @param listener a pull listener to receive update events
		 * @throws IOException on IO error
		 */
		public void load(ImageArchive archive, UpdateListener<LoadImageUpdateEvent> listener) throws IOException {
			Assert.notNull(archive, "Archive must not be null");
			Assert.notNull(listener, "Listener must not be null");
			URI loadUri = buildUrl("/images/load");
			StreamCaptureUpdateListener streamListener = new StreamCaptureUpdateListener();
			listener.onStart();
			try {
				try (Response response = http().post(loadUri, "application/x-tar", archive::writeTo)) {
					jsonStream().get(response.getContent(), LoadImageUpdateEvent.class, (event) -> {
						streamListener.onUpdate(event);
						listener.onUpdate(event);
					});
				}
				Assert.state(StringUtils.hasText(streamListener.getCapturedStream()),
						"Invalid response received when loading image "
								+ ((archive.getTag() != null) ? "\"" + archive.getTag() + "\"" : ""));
			}
			finally {
				listener.onFinish();
			}
		}

		/**
		 * Export the layers of an image as {@link TarArchive}s.
		 * @param reference the reference to export
		 * @param exports a consumer to receive the layers (contents can only be accessed
		 * during the callback)
		 * @throws IOException on IO error
		 */
		public void exportLayers(ImageReference reference, IOBiConsumer<String, TarArchive> exports)
				throws IOException {
			exportLayerFiles(reference, (name, path) -> {
				try (InputStream in = Files.newInputStream(path)) {
					TarArchive archive = (out) -> StreamUtils.copy(in, out);
					exports.accept(name, archive);
				}
			});
		}

		/**
		 * Export the layers of an image as paths to layer tar files.
		 * @param reference the reference to export
		 * @param exports a consumer to receive the layer tar file paths (file can only be
		 * accessed during the callback)
		 * @throws IOException on IO error
		 * @since 2.7.10
		 */
		public void exportLayerFiles(ImageReference reference, IOBiConsumer<String, Path> exports) throws IOException {
			Assert.notNull(reference, "Reference must not be null");
			Assert.notNull(exports, "Exports must not be null");
			URI saveUri = buildUrl("/images/" + reference + "/get");
			Response response = http().get(saveUri);
			ImageArchiveManifest manifest = null;
			Map<String, Path> layerFiles = new HashMap<>();
			try (TarArchiveInputStream tar = new TarArchiveInputStream(response.getContent())) {
				TarArchiveEntry entry = tar.getNextTarEntry();
				while (entry != null) {
					if (entry.getName().equals("manifest.json")) {
						manifest = readManifest(tar);
					}
					if (entry.getName().endsWith(".tar")) {
						layerFiles.put(entry.getName(), copyToTemp(tar));
					}
					entry = tar.getNextTarEntry();
				}
			}
			Assert.notNull(manifest, "Manifest not found in image " + reference);
			for (Map.Entry<String, Path> entry : layerFiles.entrySet()) {
				String name = entry.getKey();
				Path path = entry.getValue();
				if (manifestContainsLayerEntry(manifest, name)) {
					exports.accept(name, path);
				}
				Files.delete(path);
			}
		}

		/**
		 * Remove a specific image.
		 * @param reference the reference the remove
		 * @param force if removal should be forced
		 * @throws IOException on IO error
		 */
		public void remove(ImageReference reference, boolean force) throws IOException {
			Assert.notNull(reference, "Reference must not be null");
			Collection<String> params = force ? FORCE_PARAMS : Collections.emptySet();
			URI uri = buildUrl("/images/" + reference, params);
			http().delete(uri).close();
		}

		/**
		 * Inspect an image.
		 * @param reference the image reference
		 * @return the image from the local repository
		 * @throws IOException on IO error
		 */
		public Image inspect(ImageReference reference) throws IOException {
			Assert.notNull(reference, "Reference must not be null");
			URI imageUri = buildUrl("/images/" + reference + "/json");
			try (Response response = http().get(imageUri)) {
				return Image.of(response.getContent());
			}
		}

		public void tag(ImageReference sourceReference, ImageReference targetReference) throws IOException {
			Assert.notNull(sourceReference, "SourceReference must not be null");
			Assert.notNull(targetReference, "TargetReference must not be null");
			String tag = targetReference.getTag();
			URI uri;
			if (tag == null) {
				uri = buildUrl("/images/" + sourceReference + "/tag", "repo", targetReference.toString());
			}
			else {
				uri = buildUrl("/images/" + sourceReference + "/tag", "repo",
						targetReference.inTaglessForm().toString(), "tag", tag);
			}
			http().post(uri).close();
		}

		private ImageArchiveManifest readManifest(TarArchiveInputStream tar) throws IOException {
			String manifestContent = new BufferedReader(new InputStreamReader(tar, StandardCharsets.UTF_8)).lines()
				.collect(Collectors.joining());
			return ImageArchiveManifest.of(new ByteArrayInputStream(manifestContent.getBytes(StandardCharsets.UTF_8)));
		}

		private Path copyToTemp(TarArchiveInputStream in) throws IOException {
			Path path = Files.createTempFile("create-builder-scratch-", null);
			try (OutputStream out = Files.newOutputStream(path)) {
				StreamUtils.copy(in, out);
			}
			return path;
		}

		private boolean manifestContainsLayerEntry(ImageArchiveManifest manifest, String layerId) {
			return manifest.getEntries().stream().anyMatch((content) -> content.getLayers().contains(layerId));
		}

	}

	/**
	 * Docker API for container operations.
	 */
	public class ContainerApi {

		ContainerApi() {
		}

		/**
		 * Create a new container a {@link ContainerConfig}.
		 * @param config the container config
		 * @param contents additional contents to include
		 * @return a {@link ContainerReference} for the newly created container
		 * @throws IOException on IO error
		 */
		public ContainerReference create(ContainerConfig config, ContainerContent... contents) throws IOException {
			Assert.notNull(config, "Config must not be null");
			Assert.noNullElements(contents, "Contents must not contain null elements");
			ContainerReference containerReference = createContainer(config);
			for (ContainerContent content : contents) {
				uploadContainerContent(containerReference, content);
			}
			return containerReference;
		}

		private ContainerReference createContainer(ContainerConfig config) throws IOException {
			URI createUri = buildUrl("/containers/create");
			try (Response response = http().post(createUri, "application/json", config::writeTo)) {
				return ContainerReference
					.of(SharedObjectMapper.get().readTree(response.getContent()).at("/Id").asText());
			}
		}

		private void uploadContainerContent(ContainerReference reference, ContainerContent content) throws IOException {
			URI uri = buildUrl("/containers/" + reference + "/archive", "path", content.getDestinationPath());
			http().put(uri, "application/x-tar", content.getArchive()::writeTo).close();
		}

		/**
		 * Start a specific container.
		 * @param reference the container reference to start
		 * @throws IOException on IO error
		 */
		public void start(ContainerReference reference) throws IOException {
			Assert.notNull(reference, "Reference must not be null");
			URI uri = buildUrl("/containers/" + reference + "/start");
			http().post(uri).close();
		}

		/**
		 * Return and follow logs for a specific container.
		 * @param reference the container reference
		 * @param listener a listener to receive log update events
		 * @throws IOException on IO error
		 */
		public void logs(ContainerReference reference, UpdateListener<LogUpdateEvent> listener) throws IOException {
			Assert.notNull(reference, "Reference must not be null");
			Assert.notNull(listener, "Listener must not be null");
			String[] params = { "stdout", "1", "stderr", "1", "follow", "1" };
			URI uri = buildUrl("/containers/" + reference + "/logs", params);
			listener.onStart();
			try {
				try (Response response = http().get(uri)) {
					LogUpdateEvent.readAll(response.getContent(), listener::onUpdate);
				}
			}
			finally {
				listener.onFinish();
			}
		}

		/**
		 * Wait for a container to stop and retrieve the status.
		 * @param reference the container reference
		 * @return a {@link ContainerStatus} indicating the exit status of the container
		 * @throws IOException on IO error
		 */
		public ContainerStatus wait(ContainerReference reference) throws IOException {
			Assert.notNull(reference, "Reference must not be null");
			URI uri = buildUrl("/containers/" + reference + "/wait");
			Response response = http().post(uri);
			return ContainerStatus.of(response.getContent());
		}

		/**
		 * Remove a specific container.
		 * @param reference the container to remove
		 * @param force if removal should be forced
		 * @throws IOException on IO error
		 */
		public void remove(ContainerReference reference, boolean force) throws IOException {
			Assert.notNull(reference, "Reference must not be null");
			Collection<String> params = force ? FORCE_PARAMS : Collections.emptySet();
			URI uri = buildUrl("/containers/" + reference, params);
			http().delete(uri).close();
		}

	}

	/**
	 * Docker API for volume operations.
	 */
	public class VolumeApi {

		VolumeApi() {
		}

		/**
		 * Delete a volume.
		 * @param name the name of the volume to delete
		 * @param force if the deletion should be forced
		 * @throws IOException on IO error
		 */
		public void delete(VolumeName name, boolean force) throws IOException {
			Assert.notNull(name, "Name must not be null");
			Collection<String> params = force ? FORCE_PARAMS : Collections.emptySet();
			URI uri = buildUrl("/volumes/" + name, params);
			http().delete(uri).close();
		}

	}

	/**
	 * {@link UpdateListener} used to capture the image digest.
	 */
	private static class DigestCaptureUpdateListener implements UpdateListener<ProgressUpdateEvent> {

		private static final String PREFIX = "Digest:";

		private String digest;

		@Override
		public void onUpdate(ProgressUpdateEvent event) {
			String status = event.getStatus();
			if (status != null && status.startsWith(PREFIX)) {
				String digest = status.substring(PREFIX.length()).trim();
				Assert.state(this.digest == null || this.digest.equals(digest), "Different digests IDs provided");
				this.digest = digest;
			}
		}

	}

	/**
	 * {@link UpdateListener} used to ensure an image load response stream.
	 */
	private static class StreamCaptureUpdateListener implements UpdateListener<LoadImageUpdateEvent> {

		private String stream;

		@Override
		public void onUpdate(LoadImageUpdateEvent event) {
			this.stream = event.getStream();
		}

		String getCapturedStream() {
			return this.stream;
		}

	}

	/**
	 * {@link UpdateListener} used to capture the details of an error in a response
	 * stream.
	 */
	private static class ErrorCaptureUpdateListener implements UpdateListener<PushImageUpdateEvent> {

		@Override
		public void onUpdate(PushImageUpdateEvent event) {
			Assert.state(event.getErrorDetail() == null,
					() -> "Error response received when pushing image: " + event.getErrorDetail().getMessage());
		}

	}

}
