# Design Resources

## TL;DR
No .maister standards exist. The binding design language comes from the code: the public frontend uses Bootstrap 5.3 with FontAwesome 5 free icons, the new admin uses Lit + Shoelace with Bootstrap Icons under the rules of `docs/admin-style-guide.md`, and the legacy admin uses AngularJS with Bootstrap 3 panels and the `fa` icons.

## Inventory
- standards: `docs/admin-style-guide.md` (new admin: no ternaries, `when()`, `sl-icon` Bootstrap Icons names, colour semantics)
- css-var / theme: `frontend/public/src/styles.scss` (Bootstrap 5.3 variables, `--bs-primary`); `frontend/public/src/app/stepper/stepper.component.scss` (`.wizard2-current .badge` #007ACC → `var(--bs-primary)`)
- component-lib: public payment proxy components `frontend/public/src/app/payment/*-payment-proxy/` (template: saferpay-payment-proxy); new admin `frontend/admin/src/components/payment-method.ts`
- icon-lib: `@fortawesome/free-solid-svg-icons` 5.15.4 (`['fas','credit-card']`, registered in app.module.ts); Shoelace `sl-icon name="credit-card"`
- skill hints: none relevant
