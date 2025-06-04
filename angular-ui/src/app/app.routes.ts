import { Routes } from '@angular/router';
import { ChatComponent } from './components/chat/chat.component';
import { InitialSetupComponent } from './components/initial-setup/initial-setup.component';
import { setupGuard } from './guards/setup.guard';
import { CodebaseSettingsComponent } from './components/codebase-settings/codebase-settings.component'; // Import new component

export const routes: Routes = [
  { path: 'initial-setup', component: InitialSetupComponent },
  { path: 'codebase-settings', component: CodebaseSettingsComponent, canActivate: [setupGuard] }, // Add this route
  {
    path: 'chat',
    component: ChatComponent,
    canActivate: [setupGuard]
  },
  {
    path: 'chat/:id',
    component: ChatComponent,
    canActivate: [setupGuard]
  },
  { path: '', redirectTo: '/chat', pathMatch: 'full' },
  { path: '**', redirectTo: '/chat' }
];
