# add-auth-project — TEA run notes

```bash
mvn -pl virbius-auth,virbius-control -am test \
  -Dtest=BootstrapServiceTest,LoginAndTokenTest,AuthApplicationTest,OperatorAuthFilterTest,OperatorJwtAuthFilterTest,UiAuthControllerTest,ApiKeyAuthFilterTest
```

P0 cases: see `test-design.md`. No project E2E runner (no Playwright/Cypress).
