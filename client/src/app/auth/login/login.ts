import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthService } from '../auth.service';

/**
 * The login screen — the product's only entry point (`SEC-01`).
 *
 * The form sends nothing but an email and a password, and learns nothing but "accepted" or
 * "refused". It never shows a message that distinguishes an unknown email from a wrong password:
 * the server answers the same thing either way, and the screen must not reconstruct the
 * difference — that would hand the client the account-existence oracle the server withholds from
 * it.
 */
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  protected submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }

    this.submitting.set(true);
    this.error.set(null);

    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: () => {
        this.submitting.set(false);
        // Return wherever the user was headed, without ever leaving the application: a redirect
        // URL taken from the address bar is user input like any other. A single leading `/` is not
        // enough to call it internal — `//evil.example` is protocol-relative and names another
        // origin, so it is rejected along with anything that doesn't start with a path segment.
        const redirect = this.route.snapshot.queryParamMap.get('redirect');
        const target =
          redirect && redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/';
        void this.router.navigateByUrl(target);
      },
      error: (message: string) => {
        this.submitting.set(false);
        this.error.set(message);
        this.form.controls.password.reset();
      },
    });
  }
}
