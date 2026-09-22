import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';

import { AuthService } from '../auth/auth.service';

/**
 * The post-login screen of the foundation: who I am, and which clinics I can work in.
 *
 * There is nothing business-specific behind it, and that's intentional — J3 builds security
 * **before** the features, so that no feature inherits its gaps. What this screen shows is exactly
 * what the foundation knows: identity, assignments, and the role held in each clinic.
 *
 * It also makes visible the fixture that matters: an account assigned to two clinics with
 * different roles (`erin`) shows two rows — something no model that carries the role on the
 * account could represent.
 */
@Component({
  selector: 'app-home',
  templateUrl: './home.html',
  styleUrl: './home.css',
})
export class Home {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly me = this.auth.me;

  protected logout(): void {
    this.auth.logout().subscribe(() => void this.router.navigateByUrl('/login'));
  }
}
