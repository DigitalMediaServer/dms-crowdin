package org.digitalmediaserver.crowdin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.digitalmediaserver.crowdin.tool.CodeConversion;
import org.digitalmediaserver.crowdin.tool.SortedProperties;
import org.digitalmediaserver.crowdin.tool.TranslationFile;
import org.jdom2.Document;
import org.jdom2.Element;


/**
 * Fetch crowdin translations in this project, looking at dependencies.
 *
 * @goal fetch
 * @threadSafe
 */
public class FetchCrowdinMojo extends AbstractCrowdinMojo {

	/** The standard header for generated translation files */
	public static final String COMMENT =
		"This file is automatically generated. Please do not edit this file. " +
		"If you'd like to change the content please use crowdin.";

	/*
	 * The parameters below are duplicated in PullCrowdinMojo. Any changes must
	 * be made in both places.
	 */

	/**
	 * @parameter default-value="${localRepository}"
	 */
	protected ArtifactRepository localRepository;

	/**
	 * Sets the local repository.
	 *
	 * @param value the {@link ArtifactRepository} representing the local
	 *            repository.
	 */
	protected void setLocalRepository(ArtifactRepository value) {
		localRepository = value;
	}

	/**
	 * @component
	 * @required
	 * @readonly
	 */
	protected ArtifactFactory artifactFactory;

	/**
	 * Sets the {@link ArtifactFactory}.
	 *
	 * @param value the {@link ArtifactFactory} to set.
	 */
	protected void setArtifactFactory(ArtifactFactory value) {
		artifactFactory = value;
	}

	/**
	 * @component
	 * @required
	 * @readonly
	 */
	protected ArtifactMetadataSource artifactMetadataSource;

	/**
	 * Sets the {@link ArtifactMetadataSource}.
	 *
	 * @param value the {@link ArtifactMetadataSource} to set.
	 */
	protected void setArtifactMetadataSource(ArtifactMetadataSource value) {
		artifactMetadataSource = value;
	}

	private void cleanFolders(Set<TranslationFile> translationFiles) {
		if (downloadFolder.exists()) {
			File[] languageFolders = downloadFolder.listFiles();
			if (languageFolders != null) {
				for (File languageFolder : languageFolders) {
					if (!languageFolder.getName().startsWith(".") && languageFolder.isDirectory()) {
						if (!containsLanguage(translationFiles, languageFolder.getName())) {
							deleteFolder(languageFolder, true);
						} else {
							cleanLanguageFolder(languageFolder, translationFiles);
						}
					}
				}
			}
		}
	}

	private void cleanLanguageFolder(File languageFolder, Set<TranslationFile> translationFiles) {
		File[] entries = languageFolder.listFiles();
		if (entries != null) {
			for (File entry : entries) {
				if (!entry.getName().startsWith(".")) {
					if (entry.isDirectory()) {
						if (!containsMavenId(translationFiles, entry.getName())) {
							deleteFolder(entry, true);
						} else {
							deleteFolder(entry, false);
						}
					} else {
						if (!isLanguageFile(translationFiles, entry)) {
							if (entry.delete() && getLog().isDebugEnabled()) {
								getLog().debug("Deleted " + entry);
							} else if (getLog().isDebugEnabled()) {
								getLog().debug("Failed to delete " + entry);
							}
						}
					}
				}
			}
		}
	}

	private static boolean containsLanguage(Set<TranslationFile> translationFiles, String language) {
		for (TranslationFile translationFile : translationFiles) {
			if (translationFile.getLanguage().equals(language)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isLanguageFile(Set<TranslationFile> translationFiles, File file) {
		for (TranslationFile translationFile : translationFiles) {
			if (translationFile.getName().equals(file.getName())) {
				return true;
			}
		}
		return false;
	}

	private static boolean containsMavenId(Set<TranslationFile> translationFiles, String mavenId) {
		for (TranslationFile translationFile : translationFiles) {
			if (translationFile.getMavenId().equals(mavenId)) {
				return true;
			}
		}
		return false;
	}

	private boolean deleteFolder(File folder, boolean deleteRoot) {
		File[] listFiles = folder.listFiles();
		if (listFiles != null) {
			for (File file : listFiles) {
				if (!file.getName().startsWith(".") || deleteRoot) {
					if (file.isDirectory()) {
						deleteFolder(file, true);
					}
					if (!file.delete()) {
						return false;
					}
					getLog().debug("Deleted " + file);
				}
			}
		}
		if (deleteRoot) {
			boolean deleted = folder.delete();
			getLog().debug("Deleted " + folder);
			return deleted;
		}
		return true;
	}

	private Map<TranslationFile, byte[]> downloadTranslations(String branch) throws MojoExecutionException {
		CodeConversion conversion = getCodeConversion();
		try {
			StringBuilder url = new StringBuilder(API_URL);
			url.append(server.getUsername()).append("/download/all.zip?");
			if (branch != null) {
				url.append("branch=").append(branch).append("&");
			}
			url.append("key=");
			getLog().debug("Calling " + url + "<API key>");
			url.append(server.getPassword());
			HttpGet getMethod = new HttpGet(url.toString());
			HttpResponse response = client.execute(getMethod);
			int returnCode = response.getStatusLine().getStatusCode();
			getLog().debug("Return code : " + returnCode);

			if (returnCode == 200) {

				Map<TranslationFile, byte[]> translations = new HashMap<TranslationFile, byte[]>();

				InputStream responseBodyAsStream = response.getEntity().getContent();
				ZipInputStream zis = new ZipInputStream(responseBodyAsStream);
				try {
					ZipEntry entry;
					while ((entry = zis.getNextEntry()) != null) {
						if (!entry.isDirectory()) {

							String name = entry.getName();
							getLog().debug("Processing " + name);
							int slash = name.indexOf('/');
							String language = name.substring(0, slash);
							name = name.substring(slash + 1);
							slash = name.indexOf('/');
							String mavenId = null;
							if (slash > 0) {
								mavenId = name.substring(0, slash);
								name = name.substring(slash + 1);
							}
							if (name.matches("messages_.*\\.properties")) {
								name = "messages_" + conversion.crowdinCodeToFileTag(language) + ".properties";
							}
							TranslationFile translationFile = new TranslationFile(
								conversion.crowdinCodeToLanguageTag(language),
								mavenId,
								name
							);

							ByteArrayOutputStream bos = new ByteArrayOutputStream();
							while (zis.available() > 0) {
								int read = zis.read();
								if (read != -1) {
									bos.write(read);
								}
							}
							bos.close();
							translations.put(translationFile, bos.toByteArray());
						}
					}
				} finally {
					zis.close();
				}

				EntityUtils.consumeQuietly(response.getEntity());
				return translations;
			} else if (returnCode == 404) {
				throw new MojoExecutionException(
					"Could not find any files in branch \"" + (branch != null ? branch : rootBranch) + "\" on crowdin"
				);
			} else {
				throw new MojoExecutionException(
					"Failed to get translations from crowdin with return code " + Integer.toString(returnCode)
				);
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to call API", e);
		}
	}

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		super.execute();

		String branch = getBranch();
		getLog().info("Downloading translations from crowdin");
		Map<TranslationFile, byte[]> translations = downloadTranslations(branch);

		if (localRepository != null) {
			Set<String> mavenIds = new HashSet<String>();

			Map<TranslationFile, byte[]> usedTranslations = new HashMap<TranslationFile, byte[]>();
			usedTranslations.putAll(translations);

			for (TranslationFile translationFile : translations.keySet()) {
				if (translationFile.getMavenId() == null) {
					getLog().debug(translationFile.getName() + " is a root project file");
				} else if (!mavenIds.contains(translationFile.getMavenId())) {
					getLog().debug(translationFile.getMavenId() + " is not a dependency");
					usedTranslations.remove(translationFile);
				} else {
					getLog().debug(translationFile.getMavenId() + " is a dependency");
				}
			}
			translations = usedTranslations;
		}

		if (translations.size() == 0) {
			getLog().info("No translations available for this project!");
		} else {

			getLog().info("Cleaning crowdin folder");
			cleanFolders(translations.keySet());

			getLog().info("Copying translations to crowdin folder");
			try {
				copyTranslations(translations);
			} catch (IOException e) {
				throw new MojoExecutionException("Failed to write file", e);
			}

			downloadStatus();
		}
	}

	private void downloadStatus() throws MojoExecutionException {
		getLog().info("Downloading translation status");
		CodeConversion conversion = getCodeConversion();

		if (statusFile == null) {
			throw new MojoExecutionException("Parameter statusFile can not be empty - fetch aborted");
		} else if (statusFile.exists() && statusFile.isDirectory() || statusFile.getName() == null || statusFile.getName().equals("")) {
			throw new MojoExecutionException("Parameter statusFile must be a file - fetch aborted");
		}

		Document document = crowdinRequestAPI("status", null, null, true);
		if (!document.getRootElement().getName().equals("status")) {
			String code = document.getRootElement().getChildTextNormalize("code");
			String message = document.getRootElement().getChildTextNormalize("message");
			throw new MojoExecutionException("Failed to call API for \"status\" - " + code + " - " + message);
		}

		getLog().info("Writing translation status to file");
		SortedProperties statusProperties = new SortedProperties();
		for (Object child : document.getRootElement().getChildren("language")) {
			Element childElement = (Element) child;
			if (!childElement.getChildTextTrim("code").equals("")) {
				String languageTag = conversion.crowdinCodeToLanguageTag(childElement.getChildTextNormalize("code"));
				statusProperties.put(languageTag + ".name", childElement.getChildTextNormalize("name"));
				statusProperties.put(languageTag + ".phrases", childElement.getChildTextNormalize("phrases"));
				statusProperties.put(languageTag + ".phrases.translated", childElement.getChildTextNormalize("translated"));
				statusProperties.put(languageTag + ".phrases.approved", childElement.getChildTextNormalize("approved"));
				statusProperties.put(languageTag + ".words", childElement.getChildTextNormalize("words"));
				statusProperties.put(languageTag + ".words.translated", childElement.getChildTextNormalize("words_translated"));
				statusProperties.put(languageTag + ".words.approved", childElement.getChildTextNormalize("words_approved"));
				statusProperties.put(languageTag + ".progress.translated", childElement.getChildTextNormalize("translated_progress"));
				statusProperties.put(languageTag + ".progress.approved", childElement.getChildTextNormalize("approved_progress"));
				if (getLog().isDebugEnabled()) {
					getLog().debug(
						"Translation status for " + childElement.getChildTextNormalize("name") + "(" +
						childElement.getChildTextNormalize("code") + "): " +
						"Phrases " + childElement.getChildTextNormalize("phrases") +
						", Translated " + childElement.getChildTextNormalize("translated") +
						", Approved " + childElement.getChildTextNormalize("approved")
					);
				}
			}
		}
		File statusFile = new File(downloadFolder, this.statusFile.getName());
		try {
			FileOutputStream out = new FileOutputStream(statusFile);
			try {
				statusProperties.store(out, "This file is automatically generated, please do not edit this file.");
			} finally {
				out.close();
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write file " + statusFile.getAbsolutePath() + ": " + e.getMessage());
		}

	}
	private void copyTranslations(Map<TranslationFile, byte[]> translations) throws IOException, MojoExecutionException {
		Set<Entry<TranslationFile, byte[]>> entrySet = translations.entrySet();
		for (Entry<TranslationFile, byte[]> entry : entrySet) {
			TranslationFile translationFile = entry.getKey();

			byte[] bytes = entry.getValue();
			SortedProperties properties = new SortedProperties();
			InputStream inStream = new ByteArrayInputStream(bytes);
			try {
				properties.load(inStream);
			} finally {
				inStream.close();
			}

			File languageFolder = new File(downloadFolder, translationFile.getLanguage());
			if (!languageFolder.exists() && !languageFolder.mkdirs()) {
				throw new MojoExecutionException("Could not create folder " + languageFolder.getAbsolutePath());
			}

			File targetFile;
			if (translationFile.getMavenId() != null) {
				File mavenIdFolder = new File(languageFolder, translationFile.getMavenId());
				if (!mavenIdFolder.exists() && !mavenIdFolder.mkdirs()) {
					throw new MojoExecutionException("Could not create folder \"" + mavenIdFolder.getAbsolutePath() + "\"");
				}
				targetFile = new File(mavenIdFolder, translationFile.getName());
			} else {
				targetFile = new File(languageFolder, translationFile.getName());
			}

			getLog().info(
					"Importing " + translationFile.getLanguage() +
					(translationFile.getMavenId() == null ? "" : "/" + translationFile.getMavenId()) +
					"/" + translationFile.getName() + " from crowdin");

			FileOutputStream out = new FileOutputStream(targetFile);
			try {
				properties.store(out, COMMENT);
			} finally {
				out.close();
			}
		}
	}

	/**
	 * @return A new {@link CodeConversion} instance. Override as needed.
	 */
	protected CodeConversion getCodeConversion() {
		return new CodeConversion();
	}
}
