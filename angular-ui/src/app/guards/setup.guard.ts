import {inject} from '@angular/core';
import {CanActivateFn, Router} from '@angular/router';
import {map} from 'rxjs/operators';
import {ChatApiService} from '../services/chat-api.service'; // Corrected path

export const setupGuard: CanActivateFn = (route, state) => {
  const chatApiService = inject(ChatApiService);
  const router = inject(Router);

  return chatApiService.isSetupComplete().pipe(
    map(isConfigured => {
      if (isConfigured) {
        return true;
      }
      router.navigate(['/initial-setup']);
      return false;
    }),
  );
};
