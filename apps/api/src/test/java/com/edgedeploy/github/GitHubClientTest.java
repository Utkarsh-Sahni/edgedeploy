package com.edgedeploy.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Exercises the real RestClient configuration (headers, error mapping) against a mock GitHub. */
class GitHubClientTest {

    private static final String API = "https://api.github.test";

    private MockRestServiceServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // Same customisation as production, minus the real HTTP request factory.
        RestClient restClient = builder
                .baseUrl(API)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", GitHubConfig.API_VERSION)
                .defaultStatusHandler(status -> status.isError(), (request, response) -> {
                    throw GitHubClient.toException(response.getStatusCode(), response.getHeaders());
                })
                .build();
        client = new GitHubClient(restClient);
    }

    @Test
    void listsRepositoriesWithAuthAndVersionHeadersAndDetectsNextPage() {
        server.expect(requestTo(API + "/user/repos?sort=pushed&direction=desc"
                        + "&affiliation=owner,collaborator,organization_member&page=1&per_page=2"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer gho_token"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess("""
                        [{"id":1,"name":"portfolio","full_name":"octocat/portfolio","owner":{"login":"octocat"},
                          "description":"My site","private":true,"default_branch":"main",
                          "html_url":"https://github.com/octocat/portfolio","language":"TypeScript",
                          "pushed_at":"2026-10-01T10:00:00Z","permissions":{"admin":false,"push":true,"pull":true},
                          "some_new_github_field":42}]
                        """, MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LINK, "<" + API + "/user/repos?page=2>; rel=\"next\""));

        GitHubModels.Page<GitHubModels.Repository> page = client.listRepositories("gho_token", 1, 2);

        assertThat(page.hasNext()).isTrue();
        GitHubModels.Repository repo = page.items().getFirst();
        assertThat(repo.fullName()).isEqualTo("octocat/portfolio");
        assertThat(repo.isPrivate()).isTrue();
        assertThat(repo.defaultBranch()).isEqualTo("main");
        assertThat(repo.canDeploy()).isTrue();
        server.verify();
    }

    @Test
    void lastPageHasNoNext() {
        server.expect(requestTo(API + "/repos/octocat/portfolio/branches?page=1&per_page=100"))
                .andRespond(withSuccess("""
                        [{"name":"main","commit":{"sha":"abc"},"protected":true}]
                        """, MediaType.APPLICATION_JSON));

        GitHubModels.Page<GitHubModels.Branch> page = client.listBranches("t", "octocat", "portfolio", 1, 100);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.items().getFirst().isProtected()).isTrue();
        assertThat(page.items().getFirst().commit().sha()).isEqualTo("abc");
    }

    @Test
    void pathSegmentsAreEncodedSoInputCannotChangeTheEndpoint() {
        server.expect(requestTo(API + "/repos/octocat/portfolio/branches/feature%2Flogin"))
                .andRespond(withSuccess("""
                        {"name":"feature/login","commit":{"sha":"abc"},"protected":false}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.getBranch("t", "octocat", "portfolio", "feature/login").name()).isEqualTo("feature/login");
        server.verify();
    }

    @Test
    void mapsErrorStatusesToKinds() {
        server.expect(requestTo(API + "/repos/a/unauthorized")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(API + "/repos/a/missing")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(API + "/repos/a/limited")).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .header("X-RateLimit-Remaining", "0"));
        server.expect(requestTo(API + "/repos/a/down")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertKind("unauthorized", GitHubApiException.Kind.UNAUTHORIZED);
        assertKind("missing", GitHubApiException.Kind.NOT_FOUND);
        assertKind("limited", GitHubApiException.Kind.RATE_LIMITED);
        assertKind("down", GitHubApiException.Kind.UNAVAILABLE);
    }

    @Test
    void readsPrimaryEmails() {
        server.expect(requestTo(API + "/user/emails")).andRespond(withSuccess("""
                [{"email":"old@example.com","primary":false,"verified":true},
                 {"email":"me@example.com","primary":true,"verified":true}]
                """, MediaType.APPLICATION_JSON));

        assertThat(client.getEmails("t")).extracting(GitHubModels.Email::email)
                .containsExactly("old@example.com", "me@example.com");
    }

    private void assertKind(String repo, GitHubApiException.Kind kind) {
        assertThatThrownBy(() -> client.getRepository("t", "a", repo))
                .isInstanceOfSatisfying(GitHubApiException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }
}
