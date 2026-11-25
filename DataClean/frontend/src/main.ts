import { provideHttpClient } from '@angular/common/http';
import { bootstrapApplication } from '@angular/platform-browser';
import { Dashboard } from './dashboard';

bootstrapApplication(Dashboard, { providers: [provideHttpClient()] }).catch(console.error);
