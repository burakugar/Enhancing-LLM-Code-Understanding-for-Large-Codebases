import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CodebaseSettingsComponent } from './codebase-settings.component';

describe('CodebaseSettingsComponent', () => {
  let component: CodebaseSettingsComponent;
  let fixture: ComponentFixture<CodebaseSettingsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CodebaseSettingsComponent]
    })
    .compileComponents();

    fixture = TestBed.createComponent(CodebaseSettingsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
