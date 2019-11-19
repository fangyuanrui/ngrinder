package org.ngrinder.script.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTreeEntry;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.ngrinder.common.exception.NGrinderRuntimeException;
import org.ngrinder.model.User;
import org.ngrinder.script.model.FileEntry;
import org.ngrinder.script.model.GitHubConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.ngrinder.common.constant.CacheConstants.CACHE_GITHUB_SCRIPTS;
import static org.ngrinder.common.util.NoOp.noOp;
import static org.slf4j.LoggerFactory.getLogger;

/**
 *
 * @since 3.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubService {

	private static final String GITHUB_CONFIG_NAME = ".gitconfig.yml";

	private final FileEntryService fileEntryService;

	private final ObjectMapper objectMapper;

	public List<GitHubConfig> getGitHubConfig(User user) throws FileNotFoundException {
		List<GitHubConfig> gitHubConfig = new ArrayList<>();
		FileEntry gitConfigYaml = fileEntryService.getOne(user, GITHUB_CONFIG_NAME, -1L);
		if (gitConfigYaml == null) {
			throw new FileNotFoundException(GITHUB_CONFIG_NAME + " isn't exist.");
		}

		// Yaml is not thread safe. so create it every time.
		Yaml yaml = new Yaml();
		Iterable<Object> gitConfigs = yaml.loadAll(gitConfigYaml.getContent());
		for (Object object : gitConfigs) {
			gitHubConfig.add(objectMapper.convertValue(object, GitHubConfig.class));
		}
		return gitHubConfig;
	}

	/**
	 * Get ngrinder test scripts from user github repository.
	 *
	 * @since 3.5.0
	 */
	@Cacheable(value = CACHE_GITHUB_SCRIPTS, key = "#user.userId")
	public List<String> getScripts(User user, GitHubConfig gitHubConfig) {
		String owner = gitHubConfig.getOwner();
		String repo = gitHubConfig.getRepo();

		if (isEmpty(gitHubConfig.getOwner()) || isEmpty(gitHubConfig.getRepo())) {
			log.error("Owner and repository configuration must not be empty. [userId({}), {}]", user.getUserId(), gitHubConfig);
			throw new NGrinderRuntimeException("Owner and repository configuration must not be empty.");
		}

		try {
			GitHub gitHub = getGitHubClient(gitHubConfig);
			GHRepository ghRepository = gitHub.getRepository(owner + "/" + repo);;
			String shaOfDefaultBranch = ghRepository.getBranch(ghRepository.getDefaultBranch()).getSHA1();
			List<GHTreeEntry> allFiles = ghRepository.getTreeRecursive(shaOfDefaultBranch, 1).getTree();
			return filterScript(allFiles);
		} catch (IOException e) {
			log.error("Fail to get script from git with [userId({}), {}]", user.getUserId(), gitHubConfig, e);
			throw new NGrinderRuntimeException("Fail to get script from git.");
		}
	}

	/**
	 * Create GitHub client from {@link GitHubConfig}.
	 *
	 * @since 3.5.0
	 */
	private GitHub getGitHubClient(GitHubConfig gitHubConfig) {
		String baseUrl = gitHubConfig.getBaseUrl();
		String accessToken = gitHubConfig.getAccessToken();

		GitHubBuilder gitHubBuilder = new GitHubBuilder();

		if (isNotEmpty(baseUrl)) {
			gitHubBuilder.withEndpoint(baseUrl);
		}

		if (isNotEmpty(accessToken)) {
			gitHubBuilder.withOAuthToken(accessToken);
		}

		try {
			return gitHubBuilder.build();
		} catch (IOException e) {
			log.error("Fail to creation of github client from {}", gitHubConfig, e);
			throw new NGrinderRuntimeException("Fail to creation of github client.");
		}
	}

	private List<String> filterScript(List<GHTreeEntry> ghTreeEntries) {
		return ghTreeEntries
			.stream()
			.filter(this::isScript)
			.map(GHTreeEntry::getPath)
			.collect(toList());
	}

	private boolean isScript(GHTreeEntry ghTreeEntry) {
		String path = ghTreeEntry.getPath();
		return ghTreeEntry.getType().endsWith("blob")
			&& (path.endsWith(".groovy") || path.endsWith(".py"));
	}

	@CacheEvict(value = CACHE_GITHUB_SCRIPTS, key = "#user.userId")
	public void evictGitHubScriptCache(User user) {
		noOp();
	}
}